# [BUG] Native crash no descarte do core — `LibretroDroid::destroy()` SIGSEGV fault addr 0x80 por chamada com `core == nullptr`

**Data da correção:** 2026-09-18  
**Status:** Resolvido — AAR atualizado e regressão validada em aparelho  
**Severidade:** Alta (crash nativo)  
**Branch:** `version9`

- **Detectado em:** 2026-09-15 10:08 (telemetria de produção)
- **Origem:** telemetria `retrogamesystem/native` (`liblibretrodroid.so::libretrodroid::LibretroDroid::destroy()`)
- **Errors (serviço):** 6718 (1 ocorrência)
- **Classe:** crash / nativo
- **Reincidência:** primeira vez detectado (Samsung SM-G781B, Android 13, app 1.17.19, snes/snes9x)

---

## Sintoma

Ao fechar um jogo ou destruir a Activity (`onDestroy`), o processo `:game` crasha nativamente com SIGSEGV:

```
signal 11 (SIGSEGV), code SEGV_MAPERR, fault addr 0x80
Cause: null pointer dereference
backtrace:
  #00  pc 00000000000b8dac  .../libarm64/liblibretrodroid.so (libretrodroid::LibretroDroid::destroy()+84)
  #01  pc 00000000000b3e64  .../libarm64/liblibretrodroid.so (Java_com_swordfish_libretrodroid_LibretroDroid_destroy+44)
  #04  .../base.vdex (com.swordfish.libretrodroid.GLRetroView$onDestroy$1.invoke+6)
  #07  .../base.odex (com.swordfish.libretrodroid.GLRetroView.catchExceptions+92)
  #09  .../base.vdex (com.swordfish.libretrodroid.GLRetroView.onDestroy+16)
```

## Causa raiz

Confirmada no código C++ em `LibretroDroid-patched/libretrodroid/src/main/cpp/libretrodroid.cpp` linhas 406-425:

```cpp
void LibretroDroid::destroy() {
    LOGD("Performing libretrodroid destroy");

    if (Environment::getInstance().getHwContextDestroy() != nullptr) {
        Environment::getInstance().getHwContextDestroy()();
    }

    core->retro_unload_game();
    core->retro_deinit();

    video = nullptr;
    core = nullptr;
    rumble = nullptr;
    fpsSync = nullptr;
    audio = nullptr;
    runtimeResumed = false;

    Environment::getInstance().deinitialize();
    VFS::getInstance().deinitialize();
}
```

O ponteiro `core` é anulado (`core = nullptr`) ao final do método. Porém, **não existe guarda `if (core != nullptr)`** no início do método.
Se `destroy()` for chamado quando `core` for nulo — por exemplo:
- Uma chamada redundante durante teardown de lifecycle (`onDestroy` + `surfaceDestroyed` + `finalize`),
- Ou se a inicialização do core falhou previamente e o cleanup é disparado,
o código executa `core->retro_unload_game()`.
Como `core` é `nullptr`, o offset da tabela virtual/membro `retro_unload_game` é exatamente `0x80`. A tentativa de ler `0x0 + 0x80` causa `fault addr 0x80` (SEGV_MAPERR), o que bate perfeitamente com a tombstone.

## Como reproduzir

1. Chamar `LibretroDroid.destroy()` duas vezes consecutivas ou após falha de load.
2. Na segunda chamada, o crash com SIGSEGV `fault addr 0x80` ocorre imediatamente.

## Correção aplicada

No checkout `C:/projects/lemuroid/LibretroDroid-patched`, em
`libretrodroid/src/main/cpp/libretrodroid.cpp`, `destroy()` agora executa
o callback gráfico, `retro_unload_game()` e `retro_deinit()` somente quando
`core != nullptr`.

A limpeza dos demais recursos, de `runtimeResumed`, `Environment` e `VFS`
continua incondicional: uma criação que falhou antes de atribuir o core ainda
precisa limpar o ambiente parcial. A ordem original de descarte foi preservada,
inclusive a liberação de vídeo antes do objeto core.

`libs/libretrodroid-patched.aar` foi recompilado para `arm64-v8a`,
`armeabi-v7a`, `x86` e `x86_64`.
SHA-256: `5153b76fe40769952c7719f9453e5ef4a476680433ab6f8d7a59ca2764ca155a`.

### Instrumentação preexistente no checkout

O fonte original já continha `TEMP TEST INSTRUMENTATION` em `step()`, forçando
uma exceção no frame 120. Esse trecho foi preservado no checkout original.
O AAR foi gerado na cópia isolada `tmp/libretrodroid-destroy-build`, removendo
**somente nessa cópia** a instrumentação. Antes de distribuir futuros rebuilds
do checkout original, resolver esse trecho temporário. As quatro bibliotecas
entregues não contêm `TEMP TEST` e mantêm o tratamento JNI anterior de exceções
(`Error in step: %s`).

## Validação

- `:libretrodroid:assembleRelease --console=plain` na cópia isolada:
  **BUILD SUCCESSFUL**, quatro ABIs.
- Teste Android arm64 no aparelho `ZY32LMNN9B`, com a biblioteca real do AAR
  e um core simulado que verifica callbacks:
  destruição antes de criar; chamada repetida; callback residual sem core;
  falha real de dlopen; falha de carga com core existente;
  ordem callback gráfico → unload → deinit, exatamente uma vez;
  recriação após destruir. **Todos passaram**, exit 0.
- Controle com o AAR anterior: **Segmentation fault, exit 139** na primeira
  chamada sem core.
- `:lemuroid-app:assembleFreeBundleDebug --console=plain`:
  **BUILD SUCCESSFUL**.
- Bibliotecas dos APKs arm64-v8a e armeabi-v7a (os splits configurados no app)
  conferidas byte a byte contra o novo AAR.

Teste reproduzível a partir da raiz do Lemuroid:

```powershell
./tests/native/test-libretrodroid-destroy.ps1 -Serial ZY32LMNN9B
```

O script aceita `-SourceRoot`, `-Aar`, `-Sdk`, `-NdkVersion` e `-Serial`.
Requer aparelho Android arm64 e NDK; os caminhos padrão correspondem ao ambiente
local. Fonte: `tests/native/libretrodroid_destroy_test.cpp`.
Executa isoladamente em `/data/local/tmp`, sem instalar ou alterar o app.

O fluxo completo de UI no Samsung do relato não foi reproduzido.
A regressão valida diretamente o descarte nativo; acompanhar a telemetria após
distribuição. A guarda não adiciona sincronização para chamadas concorrentes:
o lifecycle existente continua responsável por serializar o acesso ao core.

## Lição

Teardown nativo precisa tolerar chamadas repetidas e criação incompleta.
Proteger apenas o unload deixa callbacks do core desprotegidos; retornar cedo
quando o core é nulo impede a limpeza de recursos parciais.
