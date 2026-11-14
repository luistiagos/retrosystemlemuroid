# [BUG] Crash nativo no core PSP (PPSSPP) — SIGABRT em `ES3Utils::createFramebuffer` chamado de `EmuThread`

- **Data:** 2026-10-05 22:52 (telemetria de producao)
- **Status:** 🔴 Aberto
- **Severidade:** Alta (o jogo aborta o processo `:game` ao inicializar ou redimensionar buffers)
- **Complexidade:** alta — falha nativa no pipeline GLES entre o core PPSSPP (`LibretroGLContext::SetRenderTarget`) e a camada nativa `libretrodroid` (`FramebufferRenderer` / `ES3Utils::createFramebuffer`), lançando `std::runtime_error` em thread nativa (`EmuThread`) sem tratamento de exceção.
- **Branch:** version9
- **Origem:** telemetria `retrogamesystem/native` (`liblibretrodroid.so::reason=Native crash status=6`)
- **Errors (serviço):** 9435 (1 ocorrência)
- **Aparelho:** CUBOT KINGKONG 9 (MediaTek Helio G99 / GPU Mali-G57 MC2, Android 14, arm64-v8a, app 1.17.25)

---

## Sintoma

Durante a execução de jogo de PSP, o processo `:game` aborta na thread nativa `EmuThread` com `signal 6 (SIGABRT), code SI_QUEUE` e mensagem:
`terminating due to uncaught exception of type std::runtime_error: Cannot create framebuffer`

Stack trace nativo (decodificado de Tombstone):
```text
Abort message: terminating due to uncaught exception of type std::runtime_error: Cannot create framebuffer
backtrace:
  #00  pc 000000000005c1a4  /apex/com.android.runtime/lib64/bionic/libc.so (abort+164)
  #01  pc 0000000000135308  .../lib/arm64/liblibretrodroid.so
  #02  pc 0000000000134c20  .../lib/arm64/liblibretrodroid.so
  #03  pc 0000000000134af4  .../lib/arm64/liblibretrodroid.so
  #04  pc 0000000000150948  .../lib/arm64/liblibretrodroid.so
  #05  pc 0000000000150924  .../lib/arm64/liblibretrodroid.so (__cxa_throw+88)
  #06  pc 00000000000bf758  .../lib/arm64/liblibretrodroid.so (libretrodroid::ES3Utils::createFramebuffer(unsigned int, unsigned int, bool, bool, bool, bool, std::__ndk1::optional<unsigned int>)+640)
  #07  pc 00000000000bfa24  .../lib/arm64/liblibretrodroid.so (libretrodroid::ES3Utils::buildShaderPasses(unsigned int, unsigned int, libretrodroid::ShaderManager::Chain const&)+548)
  #08  pc 00000000000bfe30  .../lib/arm64/liblibretrodroid.so (libretrodroid::FramebufferRenderer::initializeBuffers()+56)
  #09  pc 00000000000c0074  .../lib/arm64/liblibretrodroid.so (libretrodroid::FramebufferRenderer::getFramebuffer()+32)
  #10  pc 0000000000ffeb90  .../lib/arm64/libppsspp_libretro_android.so (LibretroGLContext::SetRenderTarget()+12)
  #11  pc 0000000000ffc7d4  .../lib/arm64/libppsspp_libretro_android.so
  #12  pc 0000000000ff8c74  .../lib/arm64/libppsspp_libretro_android.so
  #13  pc 0000000000d89084  .../lib/arm64/libppsspp_libretro_android.so
  #14  pc 00000000000c9ccc  /apex/com.android.runtime/lib64/bionic/libc.so (__pthread_start(void*)+204)
  #15  pc 000000000005db00  /apex/com.android.runtime/lib64/bionic/libc.so (__start_thread+64)
```

## Causa-raiz

1. O core PPSSPP invoca `LibretroGLContext::SetRenderTarget()` a partir de sua thread interna de emulação (`EmuThread`), solicitando o FBO via `libretrodroid::FramebufferRenderer::getFramebuffer()`.
2. Quando a alocação do FBO falha no driver OpenGL ES (em `glCheckFramebufferStatus != GL_FRAMEBUFFER_COMPLETE`), a função `ES3Utils::createFramebuffer` em `libretrodroid` lança uma exceção C++: `throw std::runtime_error("Cannot create framebuffer")`.
3. Como a `EmuThread` é gerenciada internamente pelo core PPSSPP e não pelo runner JNI (`GLThread`), essa exceção cruza a fronteira do DSO e não encontra nenhum bloco `try/catch`, acionando `std::terminate()` e abortando o processo.

## Próximos passos

1. Em `libretrodroid::FramebufferRenderer`, interceptar falhas de criação de framebuffer de forma segura (retornando FBO default 0 ou degradando sem lançar `std::runtime_error` que escape para threads nativas do core).
2. Investigar limites de resolução / parâmetros de FBO que disparam a recusa do driver Mali no CUBOT Kingkong 9.
