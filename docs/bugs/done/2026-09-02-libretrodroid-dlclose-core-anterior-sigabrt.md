# [BUG] SIGABRT ao abrir um jogo — `LibretroDroid::create` faz `dlclose` do core **anterior** e os destrutores estáticos dele chamam `std::terminate`

**Data:** 2026-09-02
**Status:** Corrigido 🟢 (AAR empacotado) — falta validar a troca de cores em device
**Severidade:** Alta (o app fecha ao iniciar o jogo; atinge quem troca de sistema na mesma sessão)
**Branch:** version9
**Origem:** telemetria `retrogamesystem/native` (`reason=Native crash status=6`)
**Errors (serviço):** 9 ocorrências — 3811, 3759, 3268, 2905, 1710, 1218, 1216, 1175, 1174
**Aparelhos:** Samsung SM-A515F, e outros arm64 — Android 13/15/16, app 1.17.4 → 1.17.12
**Janela observada:** 2026-08-14 16:06 → 2026-09-02 04:51 (é o crash nativo mais antigo e mais persistente)

---

## Sintoma

`signal 6 (SIGABRT), code SI_QUEUE`, `Abort message: terminating`, na **thread Main** do
processo `:game`, no momento de abrir um jogo. O backtrace liga a cadeia inteira:

```
#00  libc.so (abort+164)
#04  libppsspp_libretro_android.so (std::terminate()+36)
#05  libppsspp_libretro_android.so (std::__ndk1::thread::~thread()+28)
#06  libc.so (__cxa_finalize+280)
#07  linker64 (__dl__ZN6soinfo16call_destructorsEv+428)
#08  linker64 (__dl__ZL13soinfo_unloadP6soinfo+1268)
#09  linker64 (__dl__Z10do_dlclosePv+128)
#11  libdl.so (dlclose+8)
#12  liblibretrodroid.so (libretrodroid::Core::~Core()+24)
#13  liblibretrodroid.so (libretrodroid::LibretroDroid::resetGlobalVariables()+32)
#14  liblibretrodroid.so (libretrodroid::LibretroDroid::create(…)+108)
#15  liblibretrodroid.so (Java_com_swordfish_libretrodroid_LibretroDroid_create+1268)
#17  base.odex (com.swordfish.libretrodroid.GLRetroView$onCreate$1.invoke+832)
```

**O detalhe que fecha o diagnóstico:** o `.so` que aborta é o do **PPSSPP**, mas o breadcrumb
da sessão no mesmo report diz `system=gc; core=dolphin`. Ou seja: o usuário jogou PSP, saiu,
abriu um jogo de GameCube — e o crash é o **core antigo sendo descarregado** pelo `create()`
do novo.

## Causa-raiz

`LibretroDroid::create` começa chamando `resetGlobalVariables()`
([libretrodroid.cpp:265](../../../../LibretroDroid-patched/libretrodroid/src/main/cpp/libretrodroid.cpp#L265)):

```cpp
// libretrodroid.cpp:93 — o comentário do upstream já desconfiava
// TODO... Do we really need this?
void LibretroDroid::resetGlobalVariables() {
    core = nullptr;      // <- core é std::unique_ptr<Core>: isto DESTRÓI o Core anterior
    audio = nullptr;
    video = nullptr;
    fpsSync = nullptr;
    input = nullptr;
    rumble = nullptr;
}
```

`core` é `unique_ptr<Core>`; atribuir `nullptr` invoca `Core::~Core()`, que faz
`dlclose(libHandle)`
([core.cpp:69-78](../../../../LibretroDroid-patched/libretrodroid/src/main/cpp/core.cpp#L69-L78)):

```cpp
void Core::close() {
    if (libHandle) { dlclose(libHandle); libHandle = nullptr; }
}
Core::~Core() { close(); }
```

O processo `:game` é **único e reutilizado** entre partidas (`android:process=":game"` no
manifest), e o objeto `LibretroDroid` é global. Então, quando `destroy()` não roda (saída
abrupta, `Activity` reciclada, morte parcial), o `Core` da partida anterior **sobrevive** e é o
`create()` da partida seguinte que o descarrega.

Descarregar um core libretro com `dlclose` é operação hostil por natureza: o `dlclose` roda
`__cxa_finalize`, que executa os **destrutores de objetos estáticos** do core. Vários cores
(PPSSPP entre eles) guardam `std::thread` em estáticos e nunca dão `join`. Destruir um
`std::thread` ainda *joinable* é, por norma, `std::terminate()` — que é literalmente o frame
#04/#05 do tombstone. Não há como o core "se defender": quem escolheu o momento do `dlclose`
fomos nós.

Agrava: isso roda na **main thread** (frame #17 é o `onCreate` do `GLRetroView`), então nem o
`catchExceptions` do Kotlin ajuda — SIGABRT não desenrola pela JVM.

## Como reproduzir

Na mesma sessão do app, sem matar o processo:
1. Abrir um jogo de PSP (core `ppsspp`) e sair.
2. Abrir em seguida um jogo de outro sistema (GameCube/dolphin, no report).

A reprodutibilidade depende de o `destroy()` do primeiro jogo não ter completado — por isso
não é 100 %, mas são 9 ocorrências em 3 semanas, em 5 versões diferentes do app.

## Correção aplicada

No checkout `C:\projects\lemuroid\LibretroDroid-patched`, `Core::close()` não chama mais
`dlclose`. Ele descarta apenas o ponteiro do wrapper; a biblioteca e seus objetos estáticos
ficam carregados até o Android encerrar o processo `:game`:

```cpp
void Core::close() {
    if (libHandle) {
        // Libretro cores are not safe to unload. Some of them keep joinable threads or
        // other process-wide state in static objects, whose destructors are invoked by
        // dlclose and may abort the process. Keep the library loaded until the game
        // process exits; Android will reclaim it together with the process.
        libHandle = nullptr;
    }
}
```

Isso elimina `__cxa_finalize`/destrutores estáticos tanto no `destroy()` normal quanto na
limpeza defensiva feita pelo `create()` seguinte. O custo deliberado é reter os cores abertos
durante a vida do processo isolado; ao fim do processo, o sistema operacional recupera todo o
espaço de endereçamento.

### Por que `resetGlobalVariables()` foi mantido

Remover só a chamada no início de `create()` **não** corrige o bug: a atribuição
`core = std::make_unique<Core>(soFilePath)` na linha 280 também destrói o wrapper anterior e,
antes deste patch, também chegaria a `dlclose`, apenas alguns passos depois.

Além disso, o reset impede que callbacks da inicialização do core novo enxerguem `audio`,
`video`, `fpsSync` e `input` da sessão anterior. Com `Core::close()` seguro, ele pode continuar
limpando esse estado sem descarregar a biblioteca antiga.

## Validação

- `./gradlew.bat :libretrodroid:assembleRelease --console=plain` → **BUILD SUCCESSFUL**.
- O AAR contém `liblibretrodroid.so` para `arm64-v8a`, `armeabi-v7a`, `x86` e `x86_64`.
- `llvm-nm -D` nas quatro bibliotecas que alimentaram o AAR mostra `dlopen` e `dlsym`, mas
  nenhuma referência a `dlclose`.
- `libs/libretrodroid-patched.aar` foi substituído pelo build corrigido; SHA-256
  `3A5EEE4C81CD53E0675B385D5C9F098C2DF6B705147169A682CD5092A9EC915C`.
- `:lemuroid-app:assembleFreeBundleDebug` chegou a `mergeFreeBundleDebugNativeLibs`, mas o
  build completo foi bloqueado por erro preexistente fora deste patch:
  `GameSystem.kt:1861: Unresolved reference 'setting_citra_use_hw_shaders'`.

## Pendente

- Validar em device, na mesma sessão do processo `:game`: PSP → GameCube e Saturn → PSX.

## 🔁 Recorrência — triagem de 2026-09-03

**1 error novo: 4182.** Xiaomi 2412DPC0AG, arm64-v8a, Android 16, app **1.17.12**,
`system=psp; core=ppsspp; game=Dante's Inferno`. Mesma assinatura
(`libppsspp_libretro_android.so`, `signal 6 (SIGABRT), code SI_QUEUE`), elevando o grupo de 9
para **10 ocorrências**.

**Não é regressão.** O campo `when` do report é `2026-09-01 20:16:03` — ou seja, o crash
aconteceu **antes** de o AAR corrigido ser empacotado (2026-09-02); só a coleta pelo
`ApplicationExitInfo` caiu na sessão seguinte, em 2026-09-03. O critério de reabertura continua
sendo o mesmo: **`when` posterior à distribuição do AAR corrigido**.
