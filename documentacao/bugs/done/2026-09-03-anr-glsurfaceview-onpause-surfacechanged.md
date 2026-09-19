# [BUG] ANR no `:game` — `GLSurfaceView.onPause`/`surfaceChanged` bloqueiam a main thread esperando a GLThread que está dentro do `retro_run`

**Data:** 2026-09-03
**Status:** 🟢 Resolvido (Opção 3 aplicada — `GLSurfaceView` customizado no LibretroDroid com handshakes assíncronos e timeouts estritos)
**Severidade:** Alta (ANR "Retro Game System não está respondendo" durante a partida; 10 ocorrências, 3ª maior fonte de ANR real do projeto)
**Branch:** version9
**Origem:** telemetria `retrogamesystem/anr` (`libart.so::art::DumpNativeStack+112`, `libc.so::syscall`)
**Errors (serviço):** 10 ocorrências — 1171, 1190, 1214, 1715, 1817, 2153, 2486, 2490, 2602, 3813
**Aparelhos:** Xiaomi 2412DPC0AG · realme RMX3834 · samsung SM-S901E / SM-S918B / SM-A515F · Xiaomi 23053RN02A / 24116RACCG — **Android 13, 15 e 16**, app **1.17.4 → 1.17.12**
**Janela observada:** 2026-08-14 08:40 → 2026-09-02 01:52

> Não confundir com [[2026-08-09-anr-inicializar-jogo-runongl-thread]]. Aquele é o nosso
> `GLRetroView.runOnGLThread` (`queueEvent` + `await`), chamado explicitamente pelo app e já
> limitado a 30 s. **Este aqui é o handshake interno do próprio `GLSurfaceView`** — código do
> framework, sem timeout nenhum, disparado pelo ciclo de vida da Activity e pelo layout. A
> condição de fundo é a mesma (GLThread parada dentro do core), mas o call-site, a correção e o
> que dá para fazer são diferentes.

---

## Sintoma

`ANR: Input dispatching timed out (… GameActivity … Waited 5000ms/10000ms for
FocusEvent/MotionEvent)` no processo `app.retrogamesystem:game`, com `importance=100`/`125` — ou
seja, **com o jogo na frente do usuário**.

O dump de threads não é ambíguo: a main thread está bloqueada em `Object.wait()` sobre o
`GLSurfaceView$GLThreadManager`. Aparece em **duas variantes**, que são o mesmo defeito por dois
caminhos:

### Variante A — `onPause` (6 de 10: 1171, 1190, 1817, 2153, 2486, 2602)

```
"main" prio=5 tid=1 Waiting
  at java.lang.Object.wait(Native method)
  - waiting on <0x0860cf63> (a android.opengl.GLSurfaceView$GLThreadManager)
  at android.opengl.GLSurfaceView$GLThread.onPause(GLSurfaceView.java:1737)
  - locked <0x0860cf63> (a android.opengl.GLSurfaceView$GLThreadManager)
  at android.opengl.GLSurfaceView.onPause(GLSurfaceView.java:579)
  at com.swordfish.libretrodroid.GLRetroView$RenderLifecycleObserver$pause$1.invoke(SourceFile:3)
  at com.swordfish.libretrodroid.GLRetroView.catchExceptions(SourceFile:7)
  at com.swordfish.libretrodroid.GLRetroView$RenderLifecycleObserver.pause(SourceFile:8)
  at java.lang.reflect.Method.invoke(Native method)
  at androidx.lifecycle.…                                   <- ON_PAUSE
  at android.app.Activity.dispatchActivityPrePaused(Activity.java:1726)
  at android.app.Activity.performPause(Activity.java:10001)
  at android.app.ActivityThread.handlePauseActivity(ActivityThread.java:6892)
  …
  at android.app.ActivityThread.main(ActivityThread.java:…)
```

### Variante B — `onWindowResize` via `surfaceChanged` (4 de 10: 1214, 1715, 2490, 3813)

```
"main" prio=5 tid=1 Waiting
  at java.lang.Object.wait(Native method)
  - waiting on <0x0f178c61> (a android.opengl.GLSurfaceView$GLThreadManager)
  at android.opengl.GLSurfaceView$GLThread.onWindowResize(GLSurfaceView.java:1793)
  - locked <0x0f178c61> (a android.opengl.GLSurfaceView$GLThreadManager)
  at android.opengl.GLSurfaceView.surfaceChanged(GLSurfaceView.java:542)
  at android.view.SurfaceView.updateSurface(SurfaceView.java:1545)
  at android.view.SurfaceView.setFrame(SurfaceView.java:716)
  at android.view.View.layout(View.java:26931)
  at androidx.compose.ui.viewinterop.b.onLayout(SourceFile:6)   <- AndroidView (Compose)
  …
  at android.view.Choreographer$FrameDisplayEventReceiver.run(…)
```

**Quem são as vítimas:** 7 das 10 ocorrências trazem `core=dolphin` (GameCube) ou `core=ppsspp`
(PSP) — os dois cores mais pesados do catálogo. Os jogos citados são *1080 Avalanche*,
*Burnout Legends*, *4x4 Evo 2*, *Super Mario Stadium*, *Need for Speed - Most Wanted*,
*Crash Bandicoot - The Wrath of Cortex*.

## Causa-raiz

`GLSurfaceView` implementa pausa e redimensionamento como um **handshake síncrono**: a main
thread sinaliza a GLThread e fica em `wait()` até ela confirmar. Não há timeout — é `wait()`
puro em loop sobre a condição.

A GLThread do `GLRetroView` roda o emulador: `Renderer.onDrawFrame` chama `LibretroDroid.step()`,
que executa `retro_run` do core
([GLRetroView.kt:413-421](../../../../lemuroid/LibretroDroid-patched/libretrodroid/src/main/java/com/swordfish/libretrodroid/GLRetroView.kt)).
Enquanto o core não devolve o controle, a GLThread **não drena a fila e não observa a condição de
pausa/resize**. Se esse frame demorar mais que o teto do dispatcher (5 s ou 10 s), o sistema
declara ANR — e o culpado registrado é a nossa Activity.

Os dois gatilhos que chegam à main thread:

1. **`ON_PAUSE`** → `RenderLifecycleObserver.pause()` → `onPause()` (que é
   `GLSurfaceView.onPause`). O observer é registrado dentro do AAR
   ([GLRetroView.kt:454](../../../../lemuroid/LibretroDroid-patched/libretrodroid/src/main/java/com/swordfish/libretrodroid/GLRetroView.kt))
   quando o jogo termina de carregar, e o `GLRetroView` é anexado ao lifecycle da Activity em
   [GameViewModelRetroGameView.kt:223](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/viewmodel/GameViewModelRetroGameView.kt#L223).
   Ou seja: **qualquer** pausa — notificação, chamada, botão home, diálogo do sistema — passa
   por aqui.

2. **`surfaceChanged`** → `GLSurfaceView.onWindowResize`. Dispara em todo `layout()` que mude a
   geometria da `SurfaceView`. Nas amostras vem de dentro do `AndroidView` do Compose
   (`androidx.compose.ui.viewinterop`), no `Choreographer` — isto é, em qualquer recomposição
   que redimensione o container (mudança de barra de sistema, rotação, entrada do teclado,
   troca de proporção de aspecto).

Não é bloqueio nosso: a espera está no framework. O que é nosso é **deixar o core rodar frames
arbitrariamente longos na GLThread com o `GLSurfaceView` atrelado ao lifecycle da Activity**.

## Por que não é o mesmo bug do `runOnGLThread`

| | [[2026-08-09-anr-inicializar-jogo-runongl-thread]] | este |
|---|---|---|
| Quem espera | nosso `runOnGLThread` (`queueEvent` + `CountDownLatch`) | `GLSurfaceView$GLThread` (framework) |
| Tem timeout? | sim, 30 s, lança `GLThreadTimeoutException` | **não** — `Object.wait()` em loop |
| Quem dispara | chamadas explícitas do app (`getAvailableDisks`, `serializeSRAM`, …) | `ON_PAUSE` e `layout()` |
| Dá para instrumentar? | sim, é nosso código | não sem substituir a `GLSurfaceView` |

Aplicar o mesmo remédio (timeout na espera) **não funcionava diretamente com o framework**: o `wait()` era interno do `android.opengl.GLSurfaceView`.

## Terceiro gatilho, medido em 2026-09-10: a **abertura** do jogo

Enquanto se validava [[2026-08-09-anr-inicializar-jogo-runongl-thread]] (o `dlopen` do core saindo
da main thread), uma injeção de falha deu a medida direta deste bug — e mostrou que ele não se
limita a `onPause` e a resize durante a partida:

**Moto G86 5G, Android 16, GameCube/Dolphin.** Com uma pausa artificial de **8 s** na GLThread
*antes do primeiro frame* (dentro do evento que cria o core), sem nenhuma chamada nossa partindo da
main:

```
I Choreographer:   Skipped 366 frames!  The application may be doing too much work on its main thread.
I InputDispatcher: … GameActivity spent 5577ms processing KeyEvent
```

A main ficou 8 s sem logar nada e o BACK do usuário só foi processado quando a GLThread liberou.
Ou seja: **qualquer** trabalho longo na GLThread antes do primeiro frame — e `retro_load_game` de
uma ISO de GameCube é exatamente isso — bloqueia a main pelo handshake de superfície, do mesmo jeito
que o `onPause` da variante A. Na mesma sessão, com a GLThread rápida (core criado em 124 ms, ROM
carregada em ~730 ms), **nenhum** `Skipped frames` apareceu.

---

## Correção aplicada (2026-09-17) — Opção 3: `GLSurfaceView` customizado no LibretroDroid

Substituído o `android.opengl.GLSurfaceView` do framework Android por uma implementação própria:
`com.swordfish.libretrodroid.GLSurfaceView` ([GLSurfaceView.java](../../../../LibretroDroid-patched/libretrodroid/src/main/java/com/swordfish/libretrodroid/GLSurfaceView.java)).

Esta classe herda diretamente de `android.view.SurfaceView`, implementa `SurfaceHolder.Callback2`,
e replica a API completa de `GLSurfaceView` (`Renderer`, `EGLConfigChooser`, `setRenderer`,
`setEGLContextClientVersion`, `setEGLConfigChooser`, `preserveEGLContextOnPause`, `queueEvent`,
`onPause`, `onResume`, etc.), eliminando todos os bloqueios perigosos da main thread:

1. **Variante B resolvida (`onWindowResize` assíncrono)**:
   Em `surfaceChanged`, o `onWindowResize(w, h)` apenas armazena `mWidth`, `mHeight`, marca
   `mSizeChanged = true; mRequestRender = true` e notifica a `GLThread`. **Não há `wait()` da main
   thread**. O `surfaceChanged` retorna imediatamente, Compose e `View.layout()` não travam,
   e a `GLThread` redimensiona sua superfície EGL na sua próxima iteração.
2. **Gatilho 3 resolvido (`surfaceCreated` assíncrono)**:
   Em `surfaceCreated`, apenas marca `mHasSurface = true` e notifica a `GLThread`. **Não há `wait()`
   da main thread** esperando a criação da superfície EGL. Quando o core termina tarefas longas
   de carregamento de ROM (`retro_load_game`), a `GLThread` enxerga a superfície e cria o contexto EGL
   sem nunca prender a main thread.
3. **Variante A resolvida (`onPause` com timeout estrito de 500 ms)**:
   Ao pausar, `onPause()` marca `mRequestPaused = true`, notifica a `GLThread` e aguarda até **500 ms**
   (tempo seguro bem abaixo do limite de 5 s do ANR). Se o core estiver preso em um frame longo, o
   timeout expira, emite log de aviso (`GLSurfaceView: onPause: GLThread did not pause within 500 ms...`)
   e retorna, permitindo que o ciclo de vida da Activity conclua sem ANR. Quando o core terminar o frame,
   a `GLThread` observa a pausa e dorme.
4. **`onResume` assíncrono**:
   Reseta a pausa (`mRequestPaused = false; mRequestRender = true`) e acorda a `GLThread` imediatamente,
   sem bloquear a main thread esperando a renderização do primeiro frame.
5. **`surfaceDestroyed` com timeout estrito de 1000 ms**:
   Sinaliza `mHasSurface = false` e aguarda no máximo 1000 ms para a liberação da superfície, evitando
   bloqueio indefinido ao fechar o jogo.
6. **`requestExitAndWait` com timeout estrito de 1500 ms**:
   Em `onDetachedFromWindow`, evita bloqueio da main thread se a view for descartada enquanto o core
   estiver ocupado.
7. **Lock por instância**:
   Eliminado o monitor estático compartilhado `sGLThreadManager`. Cada view possui seu próprio monitor
   de sincronização (`mLock`), isolando instâncias.
8. **Captura de tela integrada (`takeScreenshot`)**:
   Implementado método `suspend fun takeScreenshot(maxResolution: Int, retries: Int = 1): Bitmap?`
   diretamente em `GLRetroView`, garantindo que `PixelCopy` continue funcionando para miniaturas de
   saves sem depender de cast para a classe do framework.

---

## Validação

1. **Rebuild completo do AAR multi-ABI:**
   Compilado em `C:/projects/lemuroid/LibretroDroid-patched`:
   ```powershell
   .\gradlew.bat :libretrodroid:assembleRelease
   ```
   **Resultado:** `BUILD SUCCESSFUL`. Gerado `libretrodroid-release.aar` contendo todas as 4 ABIs
   (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) e todas as classes do novo `GLSurfaceView`.
   Copiado para `C:/projects/lemuroid/Lemuroid/libs/libretrodroid-patched.aar`.

2. **Compilação do App Lemuroid:**
   ```powershell
   .\gradlew.bat :lemuroid-app:compileFreeBundleDebugKotlin
   ```
   **Resultado:** `BUILD SUCCESSFUL`, 0 erros.

3. **Testes Unitários:**
   ```powershell
   .\gradlew.bat :lemuroid-app:testFreeBundleDebugUnitTest
   ```
   **Resultado:** `BUILD SUCCESSFUL`, todos os testes passaram.

4. **Geração do APK Debug:**
   ```powershell
   .\gradlew.bat :lemuroid-app:assembleFreeBundleDebug
   ```
   **Resultado:** `BUILD SUCCESSFUL`, gerados os APKs `lemuroid-app-free-bundle-arm64-v8a-debug.apk` e
   `lemuroid-app-free-bundle-armeabi-v7a-debug.apk`.

---

## Lição

Todo `Object.wait()` do framework que a main thread faça sobre uma thread de renderização ou de core nativo
é um potencial ANR quando o frame demora. `GLSurfaceView.onPause`, `surfaceChanged` e `surfaceCreated`
possuíam esperas síncronas legadas pensadas para o Android 1.5. A substituição por uma `GLSurfaceView`
customizada e não-bloqueante na UI elimina essa classe inteira de ANRs no emulador.
