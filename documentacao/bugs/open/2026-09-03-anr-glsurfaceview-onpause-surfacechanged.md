# [BUG] ANR no `:game` — `GLSurfaceView.onPause`/`surfaceChanged` bloqueiam a main thread esperando a GLThread que está dentro do `retro_run`

**Data:** 2026-09-03
**Status:** 🔴 Aberto — causa-raiz confirmada nos dumps de thread; sem correção aplicada
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

Aplicar o mesmo remédio (timeout na espera) **não funciona aqui**: o `wait()` é do framework.

## Como reproduzir

Não reproduz sob demanda de forma confiável (é uma corrida com a duração do frame). O roteiro
com maior chance, alinhado às amostras:

1. Aparelho de médio porte, jogo de GameCube pesado no core dolphin (*Need for Speed - Most
   Wanted*, *Crash Bandicoot - Wrath of Cortex*).
2. Durante uma cena de carregamento/compilação de shader (quando o frame demora), acionar
   **pausa** — botão home, ou puxar a sombra de notificações.
3. Alternativamente, para a Variante B: forçar um resize (rotação / mostrar-esconder barras)
   no mesmo instante.

Injeção determinística (mesmo padrão do `fault_inject.py` já usado neste repositório): fazer
`Renderer.onDrawFrame` dormir 15 s uma vez, e então pausar a Activity.

## Próximos passos

- [ ] Decidir a estratégia. Três candidatas, em ordem de custo:
  1. **Não deixar o frame ser longo**: limitar o tempo de `retro_run` por frame não é possível
     do lado do app — descartada.
  2. **Desatrelar a pausa do lifecycle**: substituir o `RenderLifecycleObserver` do AAR por uma
     pausa que sinalize o core **antes** de chamar `GLSurfaceView.onPause`, de modo que a
     GLThread já esteja fora do `retro_run` quando o handshake começar. Exige mexer no
     `libretrodroid-patched`.
  3. **Trocar `GLSurfaceView` por `GLSurfaceView` própria/`SurfaceView` + loop próprio**, onde a
     espera tem timeout. É a correção real, e a mais cara.
- [ ] Medir a duração de frame por core em aparelho fraco antes de escolher — se o pico do
      dolphin já passa de 5 s em carregamento, a opção 2 sozinha não basta.
- [ ] Enquanto não houver correção, **não** contar estes ANRs como duplicata do
      `runOnGLThread`: os dois grupos devem seguir separados na telemetria.

## Lição

Todo `Object.wait()` do framework que a main thread faça sobre uma thread nossa é um ANR
esperando uma thread lenta. `GLSurfaceView.onPause` e `surfaceChanged` são dois desses, e nenhum
aparece no nosso código — só no dump de threads. Ao investigar ANR, **o frame de topo da main
thread é o diagnóstico**; a mensagem do ANR ("Input dispatching timed out") só diz que ela estava
parada, nunca por quê.
