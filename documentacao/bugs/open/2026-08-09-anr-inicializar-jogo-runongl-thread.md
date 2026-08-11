# [BUG] ANR ao inicializar jogo — main thread bloqueia em `runOnGLThread` enquanto o core carrega a ROM

**Data:** 2026-08-09
**Status:** Parcialmente corrigido 🟡 — bloqueios de main thread eliminados no app; timeout no AAR pendente (sem NDK na máquina)
**Severidade:** Alta (ANR visível ao usuário — "Retro Game System não está respondendo")
**Branch:** version9

---

## Sintoma

Cliente com Redmi relatou o diálogo do sistema **"Retro Game System não está respondendo /
Aguardar / OK"** ao iniciar um jogo. Na captura:

- A tela do jogo está **preta** (nenhum frame renderizado ainda).
- O **pad virtual já está desenhado** — L, ▶, Z, R na fileira de cima; cruz direcional +
  analógico à esquerda; cluster A/B/X/**Y** + segundo analógico à direita.

O pad identifica o sistema: esse é exatamente o layout de
[GameCube.kt](lemuroid-touchinput/src/main/java/com/swordfish/touchinput/radial/layouts/GameCube.kt)
(`Z` em `radialPosition(90f)`, `R` em `60f`, `Y` ao norte do cluster). **O jogo era de
GameCube, core Dolphin.**

O pad só é composto quando `gameState` é `Loaded`/`Ready`
([BaseGameScreen.kt:29-34](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/BaseGameScreen.kt#L29-L34)).
Ou seja: o `GameLoader` **terminou com sucesso**, o `GLRetroView` foi criado, e o
travamento acontece **depois disso** — na janela entre criar a view e o primeiro frame.

## Causa-raiz

Três bloqueios de main thread empilhados no mesmo instante. O terceiro é o que estoura os
5 s do ANR.

### 1. `runOnGLThread` espera **sem timeout** e é chamado da main thread

```kotlin
// LibretroDroid-patched/.../GLRetroView.kt
private fun <T> runOnGLThread(block: () -> T): T {
    if (Thread.currentThread().name.startsWith("GLThread")) return block()
    val latch = CountDownLatch(1)
    var result: T? = null
    queueEvent { result = block(); latch.countDown() }
    latch.awaitUninterruptibly()   // <— sem timeout, e ignora interrupt
    return result!!
}
```

`awaitUninterruptibly()` (`KtUtils.kt`) faz `await()` em loop engolindo `InterruptedException`.
Não há timeout. Quem chama isso fora da GLThread **fica preso até a GLThread drenar a fila
de eventos** — e a GLThread só drena entre callbacks do renderer.

### 2. O gatilho: o setter de `viewport`, disparado por um `LaunchedEffect` na main

```kotlin
// GLRetroView.kt
var viewport: RectF by Delegates.observable(RectF(0f,0f,1f,1f)) { _, _, value ->
    runOnGLThread { LibretroDroid.setViewport(...) }   // bloqueia quem atribui
}
```

```kotlin
// MobileGameScreen.kt:138-150
LaunchedEffect(fullPos, viewPos) {
    val gameView = viewModel.retroGameView.retroGameViewFlow()
    ...
    gameView.viewport = viewport      // LaunchedEffect roda em AndroidUiDispatcher.Main
}
```

`Delegates.observable` dispara o callback em **toda** atribuição (mesmo com valor igual ao
default), e o `LaunchedEffect` roda assim que `onGloballyPositioned` publica as duas
posições — isto é, no primeiro layout, **antes do primeiro frame**.

### 3. Do outro lado, a GLThread está dentro de `retro_load_game`

```
GLThread: onSurfaceCreated → initializeCore() → loadGameFromPath()
                                              → core->retro_load_game(&game_info)
```

`initializeCore` roda **dentro** do callback `onSurfaceCreated` do renderer
([GLRetroView.kt](/e/projects/lemuroid/LibretroDroid-patched/libretrodroid/src/main/java/com/swordfish/libretrodroid/GLRetroView.kt)),
portanto a GLThread **não volta ao loop e não drena `queueEvent`** enquanto o core não
terminar de carregar a ROM.

GameCube usa `supportsLibretroVFS = false` → `RomFiles.Standard` → `loadGameFromPath`.
O `retro_load_game` do Dolphin monta o filesystem da ISO, inicializa JIT e backend de
vídeo. Numa ISO de GameCube (até ~1,35 GB) em armazenamento de celular intermediário isso
passa dos 5 s com folga.

**Resultado: main thread parada em `latch.awaitUninterruptibly()` pelo tempo inteiro do
`retro_load_game` → ANR.**

### Agravante — `dlopen` do core também roda na main thread

`createRetroView` é chamado do `factory` do `AndroidView` (main). Dentro dele,
`lifecycle.addObserver(result)` despacha `ON_CREATE` **sincronamente**, e o observer é:

```kotlin
@OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
fun onCreate(lifecycleOwner: LifecycleOwner) = catchExceptions {
    LibretroDroid.create(...)   // → Core::Core → dlopen(core.so) → retro_init()
}
```

```cpp
// core.cpp:39
libHandle = dlopen(soCorePath.c_str(), RTLD_LOCAL | RTLD_LAZY);
```

O `dolphin_libretro_android.so` tem **14 MB** (`lemuroid-cores/lemuroid_core_dolphin/src/main/jniLibs/arm64-v8a/`).
`dlopen` + relocação de um .so C++ desse tamanho com cache de página frio custa centenas de
ms a segundos — tudo na main thread, somando ao bloqueio acima.

### Mesma classe de bug em outros dois pontos

| Local | Chamada | Thread |
|---|---|---|
| [GameViewModelSaves.kt:88-101](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/viewmodel/GameViewModelSaves.kt#L88-L101) | `restoreAutoSaveAsync` → `unserializeState()` (até 10× em loop) | `viewModelScope` = **Main** |
| [BaseGameScreenViewModel.kt:289-299](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/BaseGameScreenViewModel.kt#L289-L299) | `requestFinish` → `serializeSRAM()` / `serializeState()` | `viewModelScope` = **Main** |

Ambos passam por `runOnGLThread`. O segundo explica ANRs **ao sair** do jogo (não é o caso
desta captura, mas é o mesmo defeito). O comentário em `restoreAutoSaveAsync` diz *"Do not
change thread here. Stick to the GL one"* — mas `viewModelScope` é Main, não GL; a intenção
não se cumpre.

### Bônus — `.catch {}` do loader roda na main

Em [GameViewModelRetroGameView.kt:129-167](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/viewmodel/GameViewModelRetroGameView.kt#L129-L167),
`.flowOn(Dispatchers.IO)` cobre só o *upstream*. `.catch {}` e `.collect {}` rodam no
contexto do coletor (`lifecycleScope` = Main). Dentro do `catch`: `CoreDownloader.downloadCore`
e `BiosDownloader.downloadMissing`. O HTTP em si está protegido por `withContext(IO)`
interno, mas ficam na main: `mkdirs()`, `exists()/length()`, `AbiUtils.isElfCompatible`
(lê header ELF do disco) e, no BIOS, `md5Hex(destFile)` — hash do arquivo inteiro.

---

## Correção aplicada (lado app — no APK)

Princípio único: **nenhuma chamada que entre em `runOnGLThread` pode partir da main
thread.** O `runOnGLThread` já faz o hop para a GLThread sozinho, então mover o *chamador*
para `Dispatchers.IO` não muda em que thread o código nativo roda — só tira a main da
espera.

| Arquivo | Mudança |
|---|---|
| [MobileGameScreen.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/game/MobileGameScreen.kt) | `gameView.viewport = viewport` dentro de `withContext(Dispatchers.IO)` — **é o gatilho do ANR relatado** |
| [GameViewModelSaves.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/viewmodel/GameViewModelSaves.kt) | `getCurrentSaveState` e `loadSaveState` viraram `suspend` + `withContext(IO)`; `saveSRAM` idem; `saveQuickSave`/`loadQuickSave` viraram `suspend` |
| [BaseGameScreenViewModel.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/BaseGameScreenViewModel.kt) | `reset()` com `withContext(IO)`; `saveQuickSave`/`loadQuickSave` propagados como `suspend` |
| [BaseGameActivity.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/BaseGameActivity.kt) | `displayOptionsDialog` virou `suspend` e lê `getAvailableDisks`/`getCurrentDisk` em `withContext(IO)`; `changeDisk` movido para `lifecycleScope.launch { withContext(IO) }` |
| [GameViewModelRetroGameView.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/viewmodel/GameViewModelRetroGameView.kt) | `.flowOn(Dispatchers.IO)` movido para **depois** do `.catch`, tirando `CoreDownloader`/`BiosDownloader` (mkdirs, `isElfCompatible`, `md5Hex`) da main |

`loadSlot` perdeu o `withContext(IO)` externo — virou redundante com o wrap interno de
`loadSaveState`. `takeScreenshot` não precisou de mudança: já usa `queueEvent` +
`suspendCoroutine`, não bloqueia.

## Correção preparada (lado AAR — **não empacotada**)

Feita no checkout `E:\projects\lemuroid\LibretroDroid-patched`, **mas não compilada**: a
máquina não tem NDK instalado (`Android/Sdk/ndk/` não existe; `local.properties` aponta para
um `29.0.14206865` inexistente). O `libs/libretrodroid-patched.aar` do app segue intacto em
SHA-1 `16a6c3f8c9907c49331e6b5f0d4c7bc4a06fcbda` — **as mudanças abaixo ainda não estão no
APK** e entram no próximo rebuild do AAR.

- `runOnGLThread`: espera limitada a 30 s (`awaitUninterruptibly(timeoutMillis)`, novo
  overload em `KtUtils`, que ignora interrupt mas conta o tempo gasto contra o prazo);
  estouro lança `GLRetroView.GLThreadTimeoutException`.
- `runOnGLThread`: `countDown()` em `finally` + propagação da exceção ao chamador. **Bug
  latente separado**: se `block()` lançasse dentro do `queueEvent`, o latch nunca era
  decrementado e o chamador ficava preso *para sempre* — e a exceção morria na GLThread.
- Setter de `viewport`: `queueEvent { }` puro, sem latch (fire-and-forget; ninguém usa o
  retorno).

O fix do app **não depende** desse rebuild — ele é defesa em profundidade, e foi escrito
para sobreviver a um rollback do AAR para `.known-good`.

## Pendente

- **Empacotar o AAR** quando houver NDK (`sdkmanager "ndk;29.0.14206865"`, ~3 GB), seguindo
  a disciplina de backup + verificação de SHA-1.
- **`LibretroDroid.create` (dlopen do core, 14 MB no Dolphin) continua na main thread.**
  Não mexido: `createRetroView` roda no `factory` do `AndroidView` (obrigatoriamente main) e
  o `ON_CREATE` do `GLRetroView` é despachado sincronamente ali. Mover exige reordenar o
  ciclo de vida com cuidado — `create()` precisa acontecer antes de `onSurfaceCreated`, e
  errar isso é race de inicialização do core. Sozinho não causa o ANR.
- **Pad aparecendo sobre tela preta.** Descartada a ideia de só mostrar a tela de jogo após
  `FrameRendered`: o `AndroidView` que cria o `GLRetroView` está *dentro* do
  `gameScreen(viewModel)`, que só compõe quando o estado é `Loaded`/`Ready` — gatear nisso
  faria `createRetroView` nunca ser chamado (deadlock). Só o pad poderia ser gateado, o que
  não tira a tela preta.

## Validação

- `./gradlew.bat :lemuroid-app:compileFreeBundleDebugKotlin` → **BUILD SUCCESSFUL**, sem
  warning novo (só os pré-existentes de deprecation).
- **Não testado em device.** Roteiro pendente: repro em device intermediário com jogo de
  GameCube; confirmar via `adb shell dumpsys activity anr` que a main **não** aparece mais
  em `CountDownLatch.await` ← `GLRetroView.runOnGLThread`.

## Lição

`GLSurfaceView.queueEvent` + `CountDownLatch.await()` sem timeout é um bloqueio de duração
**ilimitada** — a GLThread pode estar dentro de um callback do renderer que demora o que o
core quiser. Nenhuma chamada `runOnGLThread` pode partir da main thread.

Ver também [2026-08-09-telemetria-nao-captura-anr.md](2026-08-09-telemetria-nao-captura-anr.md).
