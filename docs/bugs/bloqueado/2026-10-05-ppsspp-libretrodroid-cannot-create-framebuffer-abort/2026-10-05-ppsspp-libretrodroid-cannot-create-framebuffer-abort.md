---
fecha_por: prova
casador: {texto: "Cannot create framebuffer", componente: "retrogamesystem/native"}
motivo: "Causa no nativo do libretrodroid-patched.aar: FramebufferRenderer::getFramebuffer (patch do Saturn) realoca FBOs na EmuThread do PPSSPP, sem contexto EGL, e createFramebuffer lanca std::runtime_error -> SIGABRT; destrava com o dono aplicando o fix nativo descrito no doc (realocar so com contexto GL corrente / no step, sem throw) e rebuildando o AAR."
---
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

---

## Planejamento (pipeline 20261119-2228, 2026-11-19)

**Veredito:** bloqueado (zero tasks): a causa está no código nativo do `libretrodroid-patched.aar` (`FramebufferRenderer::getFramebuffer` realoca FBOs fora da thread GL). Pelo manual, mudança que exige rebuild do AAR (NDK, checkout externo `../LibretroDroid-patched`) é `bloqueado`. **Destrava:** o dono aplicar o fix nativo descrito em "Correção proposta" no checkout do LibretroDroid, gerar o AAR novo e commitá-lo em `libs/` (com o `.patch` em `libs/libretrodroid-patches/`). Depois disso, a reprodução abaixo prova a correção nesta máquina.
**Complexidade:** alta (JNI/LibretroDroid nativo, critério do `docs/bugs/aberto/README.md`).
**Severidade:** alto. O processo `:game` aborta e o jogo de PSP se perde sem salvar. Não atinge todo usuário: só quem troca a resolução do PSP com o jogo aberto. O contorno é trocar a resolução e reabrir o jogo.
**Arquivos a alterar (quando destravar):** `libs/libretrodroid-patched.aar` (binário gerado de `../LibretroDroid-patched/libretrodroid/src/main/cpp/renderers/es3/framebufferrenderer.cpp` e `es3utils.cpp`), mais `libs/libretrodroid-patches/<novo>.patch`.

### Causa raiz (símbolos abertos)

A causa é uma regressão do nosso próprio patch do Saturn, `docs/bugs/concluido/2026-08-13-saturn-tela-preta-yabasanshiro/`.

- `../LibretroDroid-patched/.../framebufferrenderer.cpp::FramebufferRenderer::getFramebuffer`: chama `reallocateIfDirty()` antes de devolver o nome do FBO. Esse é o patch local do Saturn: a realocação saiu do `onNewFrame` (GLThread) e foi para dentro do `get_current_framebuffer()` do core. `git diff` no checkout mostra a mudança, e o `onNewFrame` ficou vazio.
- `framebufferrenderer.cpp::reallocateIfDirty` → `initializeBuffers` → `ES3Utils::buildShaderPasses` → `ES3Utils::createFramebuffer`: faz `glGen*`, `glTexStorage2D` e `glCheckFramebufferStatus`. Em erro, `createFramebuffer` faz `throw std::runtime_error("Cannot create framebuffer")` (`es3utils.cpp:78`), e nenhum chamador tem `try`.
- `ppsspp_src/libretro/libretro.cpp::EmuFrame` (linha 1358): `ctx->SetRenderTarget()` roda na **EmuThread** (`EmuThreadFunc`, `SetCurrentThreadName("EmuThread")`). O `useEmuThread = ctx->GetGPUCore() == GPUCORE_GLES` (linha 1511) vale sempre no LibretroDroid, que só fala GLES.
- `ppsspp_src/libretro/LibretroGLContext.h::SetRenderTarget`: `g_defaultFBO = hw_render_.get_current_framebuffer();`. Isso chega a `LibretroDroid::handleGetCurrentFrameBuffer` → `Video::getCurrentFramebuffer` → `FramebufferRenderer::getFramebuffer` na EmuThread. A EmuThread **não tem contexto EGL corrente**: o contexto é da GLThread, onde roda o `ThreadFrame` do `GLRenderManager`. Sem contexto, as chamadas GL não fazem nada e o `glCheckFramebufferStatus` devolve 0, diferente de `GL_FRAMEBUFFER_COMPLETE`. O resultado é o throw, `std::terminate` e SIGABRT, exatamente a pilha do Sintoma (`SetRenderTarget+12` → `getFramebuffer+32` → `initializeBuffers+56` → `buildShaderPasses` → `createFramebuffer+640` → `__cxa_throw`).
- **Quando o `isDirty` liga**, em `FramebufferRenderer::updateRenderedResolution`: tamanho diferente vindo de `Video::updateRendererSize`, chamado em `LibretroDroid::step` depois do `retro_run` quando `Environment::isGameGeometryUpdated()`. Essa flag é ligada por `RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO`/`SET_GEOMETRY` (`environment.cpp:310/330`). Também liga por `setShaders` com uma cadeia diferente.
- **Gatilho no PPSSPP**, em `libretro.cpp::check_variables` (linhas ~1112-1157): ao mudar `ppsspp_internal_resolution` com o jogo rodando, ele emite `SET_SYSTEM_AV_INFO` com o tamanho novo. Trocar "crop 16:9" emite `SET_GEOMETRY`. O app expõe `ppsspp_internal_resolution` no menu do jogo (`retrograde-app-shared/.../GameSystem.kt:890`) e aplica ao vivo: `GameViewModelRetroGameView::updateCoreVariables` → `retroGameView.updateVariables` (linha 504).
- O quadro `buildShaderPasses` → `createFramebuffer` na pilha mostra que o cliente estava com o modo HD média/alta (`ShaderChooser::getMediumQualityHdMode`/`getHighQualityHdMode`, CUT2/CUT3, cadeia de 2 a 3 passes). Com o shader de passe único a pilha mudaria (o throw sairia do `createFramebuffer` do próprio `initializeBuffers`), mas o crash é o mesmo. O shader **não** é a causa.

### Provas (comando → resultado)

- `llvm-nm -D -C jni/arm64-v8a/liblibretrodroid.so` do `libs/libretrodroid-patched.aar` da base → existem `FramebufferRenderer::reallocateIfDirty()` em `0xc00e8` e `onNewFrame` em `0xc00e4` (4 bytes, vazio). O AAR distribuído **contém** o patch que realoca no `getFramebuffer`.
- `grep -n "SetRenderTarget" ppsspp_src/libretro/libretro.cpp` → um único call-site, `EmuFrame` (linha 1358), executado só por `EmuThreadFunc`.
- `grep -rn "Cannot create framebuffer" LibretroDroid-patched/libretrodroid/src/main/cpp` → só em `es3utils.cpp:78`.

### Hipóteses descartadas

- **"O driver Mali recusa o tamanho do FBO"** (Próximo passo 2 da 1ª passada): não se sustenta. A Mali-G57 aceita 8192², e a resolução do PSP vai no máximo a 10×480. O status 0 sem contexto já explica a falha em qualquer GPU.
- **"Falta try/catch no JNI"**: o throw acontece na EmuThread do core, que nenhum try do runner JNI cobre. Engolir a exceção também não basta, porque o GL continuaria sem contexto naquela thread.
- **Shader HD como causa**: ele só decide em qual `createFramebuffer` o throw sai.
- **Mitigação só no app**, sem aplicar `ppsspp_internal_resolution` ao vivo e exigindo reabrir o jogo: possível em `GameViewModelRetroGameView::updateCoreVariables`, mas mascara um defeito do LibretroDroid que afeta qualquer core que chame `get_current_framebuffer` fora da GLThread. Também troca o comportamento do menu e não cobre o "crop 16:9". Fica como alternativa se o dono não quiser rebuildar o AAR agora.

### Correção proposta (para quem destravar, no checkout do LibretroDroid)

1. `FramebufferRenderer::getFramebuffer`: só chamar `reallocateIfDirty()` quando `eglGetCurrentContext() != EGL_NO_CONTEXT`, ou seja, na thread do contexto. Fora dela, devolver o nome estável (que o patch do Saturn já garante) sem tocar em GL.
2. Manter também a realocação na GLThread: `reallocateIfDirty()` no início do `LibretroDroid::step`, antes do `retro_run`. O Saturn continua sem perder frame, porque o YabaSanshiro pede o FBO na GLThread e realoca ali.
3. `ES3Utils::createFramebuffer`: em vez de `throw`, logar e degradar. Nenhuma exceção C++ pode sair para a thread de um core.
4. Rebuild do AAR, aplicando os `libs/libretrodroid-patches/*.patch` existentes. A `verifyLibretroDroidBridge` tem de passar.

### Tarefas

Nenhuma (bloqueado). A correção é nativa, no AAR.

### Plano de teste (para quando destravar)

- **Offline:** `llvm-nm`/`llvm-objdump` do AAR novo: o `getFramebuffer` chama `eglGetCurrentContext` antes do `reallocateIfDirty`. A `verifyLibretroDroidBridge` passa no `build`.
- **T2, reprodução como o usuário:** no aparelho, com o app debug, abrir um jogo de PSP baixado pela Home → PSP → jogo. Opcional: Ajustes com Modo HD ligado, qualidade média, como o cliente. Com o jogo rodando, abrir o menu do jogo → Opções do core → **Resolução interna** → escolher outro valor → voltar ao jogo. **Sinal sem o fix:** o processo `app.retrogamesystem.debug:game` morre, e o `adb logcat` mostra `Abort message: terminating due to uncaught exception of type std::runtime_error: Cannot create framebuffer`, com `Error while creating framebuffer. Leaving!` logo antes, tid = EmuThread. **Com o fix:** o jogo continua, na resolução nova, sem esse log.
- **Controle positivo:** o APK da base (AAR atual) tem de abortar na troca de resolução. Pela análise vale em qualquer GPU, emulador incluso.
- **T3, regressão:** Saturn (YabaSanshiro): trocar o modo de vídeo e confirmar que a tela não fica preta (regressão do bug de 2026-08-13). PSP com shader padrão e com HD alto. Um core GL de thread única (N64 ou Dreamcast) abrindo e saindo.

### Sessões seguintes

Não há: bloqueado. Quando destravar, dev effort `xhigh` com ultrathink (threading nativo GL/EGL entre core e frontend) e teste `high`.
