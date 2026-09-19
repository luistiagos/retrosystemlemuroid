# [BUG] SIGSEGV em `LibretroDroid::resume()` — `fpsSync->reset()` sem guarda de null, ao contrário de `step()`

**Data:** 2026-09-02
**Status:** Resolvido ✅ — guardas aplicadas no código nativo e AAR recompilado
**Severidade:** Média (crash no retomar do jogo; 1 ocorrência, mas a correção é trivial e o
padrão de defesa já existe duas linhas abaixo)
**Branch:** version9
**Origem:** telemetria `retrogamesystem/native` (`liblibretrodroid.so::libretrodroid::FPSSync::reset()`)
**Errors (serviço):** 3760
**Aparelho:** Xiaomi 2201117SG (Redmi), arm64-v8a, Android 13, app 1.17.9 —
`system=neogeo; core=fbneo; game=The King of Fighters 2002`
**Observado em:** 2026-09-01 16:03

---

## Sintoma

```
signal 11 (SIGSEGV), code SEGV_MAPERR, fault addr 0x20
Cause: null pointer dereference
  #00  liblibretrodroid.so (libretrodroid::FPSSync::reset()+0)
  #01  liblibretrodroid.so (libretrodroid::LibretroDroid::resume()+204)
  #02  liblibretrodroid.so (Java_com_swordfish_libretrodroid_LibretroDroid_resume+44)
  #05  base.vdex (com.swordfish.libretrodroid.GLRetroView$RenderLifecycleObserver$resume$1.invoke)
  #13  base.vdex (com.swordfish.libretrodroid.GLRetroView$RenderLifecycleObserver.resume+14)
  #19  androidx.lifecycle.c$b.a
```

`fault addr 0x20` com `FPSSync::reset()+0` é a assinatura clássica de **`this == nullptr`**:
o offset 0x20 é um campo do objeto que não existe. O gatilho é o `ON_RESUME` do
`LifecycleObserver` do `GLRetroView`.

## Causa-raiz

[libretrodroid.cpp:422-430](../../../../LibretroDroid-patched/libretrodroid/src/main/cpp/libretrodroid.cpp#L422-L430):

```cpp
void LibretroDroid::resume() {
    LOGD("Performing libretrodroid resume");
    input = std::make_unique<Input>();
    fpsSync->reset();     // <- sem guarda
    audio->start();       // <- sem guarda
    refreshAspectRatio();
}
```

Contraste direto com `step()`, logo abaixo, que **já** trata o mesmo ponteiro como opcional
([libretrodroid.cpp:439-448](../../../../LibretroDroid-patched/libretrodroid/src/main/cpp/libretrodroid.cpp#L439-L448)):

```cpp
void LibretroDroid::step() {
    unsigned frames = 1;
    if (fpsSync) {                      // <- aqui a guarda existe
        unsigned requestedFrames = fpsSync->advanceFrames();
        frames = std::min(requestedFrames, 2u);
    }
    ...
}
```

E `fpsSync` é zerado em **dois** lugares:

- `destroy()` — [libretrodroid.cpp:415](../../../../LibretroDroid-patched/libretrodroid/src/main/cpp/libretrodroid.cpp#L415)
- `resetGlobalVariables()`, chamado no início de `create()` —
  [libretrodroid.cpp:93-101](../../../../LibretroDroid-patched/libretrodroid/src/main/cpp/libretrodroid.cpp#L93-L101)

Basta o `ON_RESUME` do lifecycle chegar depois de um `destroy()` (ou na janela em que
`create()` já zerou os globais e ainda não os repovoou) para `fpsSync` ser null. O ciclo de
vida do Android **não garante** essa ordem: `ON_RESUME` pode ser entregue a um observer cujo
core já foi destruído — voltar de background, `Activity` recriada, ou a corrida com o
`create()` descrita em [[2026-09-02-libretrodroid-dlclose-core-anterior-sigabrt]].

`audio->start()` na linha seguinte tem exatamente o mesmo problema latente.

## Como reproduzir

Não reproduzido de forma determinística (1 ocorrência). O caminho é a corrida entre
`ON_RESUME` e `destroy()`/`create()`: sair do jogo e voltar rapidamente, ou receber um
`ON_RESUME` durante a troca de jogo.

## Correção aplicada

`resume()` agora só recria o input e retoma a execução quando o runtime inteiro está
inicializado. Isso cobre tanto o callback posterior a `destroy()` quanto a janela em que
`create()` já publicou o `core`, mas o carregamento ainda não criou `fpsSync`, `audio` e
`video`:

```cpp
void LibretroDroid::resume() {
    LOGD("Performing libretrodroid resume");

    if (!core || !fpsSync || !audio || !video) {
        LOGD("Ignoring libretrodroid resume without an initialized runtime");
        return;
    }

    input = std::make_unique<Input>();
    fpsSync->reset();
    audio->start();
    refreshAspectRatio();
}
```

Também foram protegidos os entry points do mesmo ciclo de renderização:

- `pause()` só chama `audio->stop()` quando há áudio, mas sempre descarta o input;
- `step()` retorna antes de `retro_run()` quando o core já foi destruído;
- `refreshAspectRatio()` só atualiza o vídeo quando o renderer existe.

O AAR recompilado, contendo `arm64-v8a`, `armeabi-v7a`, `x86` e `x86_64`, substituiu
`libs/libretrodroid-patched.aar`.

## Validação

- `:libretrodroid:assembleRelease`: **BUILD SUCCESSFUL** (compilação C++ nas quatro ABIs).
- `:lemuroid-app:assembleFreeBundleDebug`: **BUILD SUCCESSFUL** com o AAR recompilado.
- SHA-256 do AAR integrado: `30B38F1709E5AD30A4EDFE512E106F79DD9F399B9D1CF964418F2D3FD0735659`.
- A reprodução física continua pendente porque a ocorrência depende de uma corrida rara de
  lifecycle; a validação atual comprova compilação e empacotamento, não ausência estatística
  do crash em aparelho.

## Próximos passos

- [x] Aplicar as guardas, rebuildar o AAR e integrá-lo ao app.
- [x] Proteger os callbacks adjacentes de pause/render contra teardown já concluído.
- [ ] Validar em aparelho alternando rapidamente entre sair, voltar e trocar de jogo.
