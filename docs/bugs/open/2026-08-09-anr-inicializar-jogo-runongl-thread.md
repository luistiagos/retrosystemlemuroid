# [BUG] ANR ao inicializar jogo — main thread bloqueia em `runOnGLThread` enquanto o core carrega a ROM

**Data:** 2026-08-09
**Status:** 🟡 (2026-10-02: o report de timeout passou a levar a pilha da GLThread — ver a última seção) Todos os bloqueios de main thread **nossos** foram corrigidos e validados em device — o
último resíduo, o `dlopen` do core, saiu da main em 2026-09-10 (ver seção própria). Segue **aberto**
apenas porque a causa de fundo (GLThread travando dentro do core) não é nossa e não reproduz sob
demanda: o critério de fechamento é a telemetria parar de acusar `GLThreadTimeoutException` em
`runOnGLThread`. O outro caminho de ANR — o handshake do próprio `GLSurfaceView` — tem página
própria: [[2026-09-03-anr-glsurfaceview-onpause-surfacechanged]]
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

## Correção aplicada (lado AAR — **empacotada**)

Feita no checkout `E:\projects\lemuroid\LibretroDroid-patched` e compilada no rebuild de
2026-08-13 (o do FBO do Saturn). Está no `libs/libretrodroid-patched.aar` atual, SHA-1
`304ec039c37aa75625f6f79648c39e9622ad5fb4` — **verificado extraindo o `classes.jar` e
confirmando a presença de `GLRetroView$GLThreadTimeoutException.class`**, não pelo hash.

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

> ⚠️ Esta seção esteve registrada por engano como "não empacotada, sem NDK na máquina".
> Era falso: o SDK real fica em `E:\DevCaches\Android\Sdk` (via `ANDROID_HOME`) e **tem**
> NDK — o `local.properties` é que aponta para um `C:\Users\...` inexistente. Checar
> `local.properties` em vez de `$env:ANDROID_HOME` leva à conclusão errada de que não dá
> para buildar nativo.

## Pendente

- ~~**`LibretroDroid.create` (dlopen do core, 14 MB no Dolphin) continua na main thread.**~~ →
  **resolvido em 2026-09-10**, ver "O `dlopen` saiu da main thread" abaixo.
- **Pad aparecendo sobre tela preta.** Descartada a ideia de só mostrar a tela de jogo após
  `FrameRendered`: o `AndroidView` que cria o `GLRetroView` está *dentro* do
  `gameScreen(viewModel)`, que só compõe quando o estado é `Loaded`/`Ready` — gatear nisso
  faria `createRetroView` nunca ser chamado (deadlock). Só o pad poderia ser gateado, o que
  não tira a tela preta.

## Validação

- `./gradlew.bat :lemuroid-app:compileFreeBundleDebugKotlin` → **BUILD SUCCESSFUL**, sem
  warning novo (só os pré-existentes de deprecation).
- `./gradlew.bat assembleFreeBundleRelease` → **BUILD SUCCESSFUL**, incluindo `lintVital` e
  R8. APKs gerados: arm64-v8a (106 MB) e armeabi-v7a (90 MB).
- Presença do fix do AAR conferida por extração do `classes.jar` (ver acima).
- ~~**Não testado em device.**~~ → **testado em 2026-09-03**, ver a seção abaixo.

> Nota de ambiente: o `assembleFreeBundleRelease` chegou a falhar duas vezes por **OOM da
> JVM** (`paging file is too small`, `G1 virtual space`), não por código. Causa: daemons
> Gradle acumulados + `org.gradle.parallel=true` com `-Xmx2560m`. Contorno usado, sem
> alterar `gradle.properties`: `--no-daemon --no-parallel --max-workers=1 -Xmx1536m`.

## Validação em device (2026-09-03) — e um terceiro ponto que ainda quebrava

**Aparelho:** Moto G86 5G, Android 16, app `1.17.12-DEBUG`.
**Jogo:** *Need for Speed - Underground 2* (GameCube / dolphin) — **o mesmo jogo e core que a
telemetria nomeia**.

### O que passou

- **Boot sem ANR.** O jogo carrega e roda a 60 fps (`EMUFPS 60.00`). O sintoma do relato
  original — pad de GameCube desenhado sobre tela preta — aparece, mas **sem** o diálogo
  "não está respondendo": é o intro do jogo, não a main thread presa.
- **Saída limpa** em todas as sessões testadas (SNES, 3DS, GameCube):
  `Stored sram file with size: …` → `System.exit called, status: 0` → `Process exited
  cleanly (0)`. Nenhum `GLThreadTimeoutException` escapou do `saveOnExit`.

### O que NÃO passou — bug novo, encontrado aqui

Numa das sessões o Dolphin **parou de renderizar** (zero `EMUFPS`/`VIDEOFRAMES` por 12 s
seguidos, processo `:game` vivo, atividade resumida): a GLThread parada dentro de um callback
do renderer, exatamente a condição de fundo desta página. Com ela nesse estado, **abrir o menu
do jogo matava a sessão**:

```
E BaseGameActivity: com.swordfish.libretrodroid.GLRetroView$GLThreadTimeoutException:
                    GLThread did not answer in 30000 ms
I ActivityTaskManager: START … GameCrashActivity
```

O caminho é o terceiro da tabela "Mesma classe de bug em outros dois pontos", e o que faltava
nele não era o `withContext(IO)` (esse já estava) e sim o **tratamento do timeout**:
`displayOptionsDialog` chamava `getAvailableDisks`/`getCurrentDisk` sem `try/catch`, a exceção
escapava do `collect` de `initializeViewModelsEffectsFlow`, chegava ao
`UncaughtExceptionHandler` e o usuário via a tela de crash — **em vez do menu, que é por onde
ele sairia do jogo travado**. Pior: o crash só chegava 30 s depois do toque, então na prática
o menu "não abria" e depois o app "quebrava sozinho".

### Correção (2026-09-03)

[BaseGameActivity.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/BaseGameActivity.kt):
`readDiskState()` substitui a leitura crua.

1. **Sonda barata antes** (`queueEvent` + latch de 2 s, o mesmo padrão de `glThreadResponds`
   em `GameViewModelSaves`): GLThread parada custa 2 s, não 30 s.
2. **`try/catch` mesmo assim** — a parada pode começar entre a sonda e a chamada.
3. Degrada para `0 to 0`: o menu abre **sem a linha de discos**, que é informação acessória,
   em vez de não abrir.
4. Reporta à telemetria com `phase=open-menu` (não-terminal), para a recorrência aparecer sem
   custar uma sessão do usuário.

### Validação da correção, no mesmo aparelho

- Menu abre em **1.181 ms** com a GLThread saudável (900 ms dos quais são o *hold* que o
  próprio botão exige) — a sonda não cobra nada no caminho normal.
- A sessão de GameCube sobreviveu a abrir/fechar o menu, tela apagada + PIN e ida e volta para
  o background, voltando sempre a 60 fps.
- **A travada da GLThread não voltou a acontecer sob demanda.** Ocorreu uma vez e não
  reproduziu em ~15 min de tentativa dirigida (screen-off/on, background/foreground, jogo
  parado). Ou seja: o caminho corrigido está exercitado no estado saudável e o crash está
  provado no estado travado **antes** da correção; o "depois" no estado travado depende de a
  travada reaparecer. É por isso que esta página continua em `open/`.

### A tela de crash acusava o núcleo — corrigido (2026-09-03)

O disclaimer exibido foi o de **falha de núcleo** ("limpe o cache… execute a formatação de
fábrica"), porque `isEmulatorFailure` casava o frame `com.swordfish.libretrodroid` e a
`GLThreadTimeoutException` é lançada de dentro de `GLRetroView.runOnGLThread`. Além do texto,
`isEmulatorFailure = true` também dispara `tryFallbackCore`: o app relançaria o jogo com o
próximo núcleo e pagaria **outro** timeout de 30 s.

Nenhum dos dois textos existentes servia:

| Texto | Por que não serve para uma GLThread travada |
|---|---|
| `lemuroid_crash_disclamer` | manda limpar dados e resetar de fábrica — nada disso alcança uma thread presa |
| `lemuroid_app_error_disclamer` | afirma "o problema não é do seu jogo, da sua ROM **nem do núcleo de emulação**", e o núcleo é exatamente o que parou |

**Correção:** terceira categoria, própria.

- [BaseGameActivity.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/BaseGameActivity.kt):
  `causeChain()` unifica a caminhada pelas causas (com o limite de profundidade que já existia);
  `isCoreStall()` procura `GLRetroView.GLThreadTimeoutException` na cadeia; e `isEmulatorFailure`
  ganhou `return false` para essa exceção **antes** do teste por pacote — sem isso ela sempre
  casaria `LIBRETRODROID_PACKAGE`. O resultado vai no extra novo
  `PLAY_GAME_RESULT_IS_CORE_STALL`.
- [GameLaunchTaskHandler.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/main/GameLaunchTaskHandler.kt):
  ramo próprio antes dos outros dois — mostra `lemuroid_core_stalled_disclamer` e **não** chama
  `tryFallbackCore`.
- String nova em `values/` e `values-pt-rBR/` (os dois locais onde o disclaimer de app vive):
  "O núcleo de emulação parou de responder e o jogo precisou ser fechado. O problema não é do
  seu aparelho nem da sua ROM — limpar dados ou resetar de fábrica não vai adiantar. Abra o jogo
  de novo; o progresso desde o último salvamento pode ter se perdido."

**Validado em device (2026-09-03)** com a exceção forçada — o mesmo roteiro que a página do
Toast já prevê para conferir a blindagem, já que a travada real não reproduz sob demanda. Build
temporário com `postDelayed { throw … }` no `onCreate`, duas rodadas, e o `.kt` conferido por
`diff` contra a cópia limpa depois de remover a instrumentação:

| Exceção forçada | Log | Tela |
|---|---|---|
| `GLRetroView.GLThreadTimeoutException` | `W GameLaunchTaskHandler: Core stalled the GL thread:` e **nenhum** `Core fallback:` | texto novo do núcleo travado |
| `IllegalStateException` | `W GameLaunchTaskHandler: Non-emulator failure in game process:` | disclaimer de app, como antes |

Nos dois casos o `text2` seguiu trazendo a mensagem real (`GLThread did not answer in 30000 ms`)
e o rodapé, aparelho + Android + versão — que é o que o pitfall 8 pede que uma foto da tela
resolva. Instrumentação removida e o build limpo reinstalado e conferido rodando um jogo a
60 fps.

### O caminho de ERRO do `saveOnExit`, exercitado por falha injetada (2026-09-03)

A validação acima cobre o caminho feliz e o caminho do **menu** com a GLThread parada. Faltava o
`saveOnExit` **falhando** — que é justamente o que a telemetria de produção mostra (errors
3810/3359/3358) e onde mora a decisão de projeto: não derrubar a saída do jogo por causa de uma
gravação que não deu, e avisar em vez de perder a SRAM em silêncio.

A travada real não reproduz sob demanda (ocorreu uma vez em ~15 min de tentativa dirigida), então
foi usada falha injetada — o mesmo recurso que esta página já usou para validar a classificação da
tela de crash. Build temporário com `serializeSRAM` lançando `GLThreadTimeoutException` sempre,
empacotado como um APK à parte do mesmo commit que o limpo.

**Moto G86 5G, Android 16, *Super Mario World* (snes/snes9x), saída pelo BACK:**

| O que tinha que acontecer | Observado |
|---|---|
| tentar de novo antes de desistir | `W GameViewModelSaves: SRAM save timed out (attempt 1/2)` e `(attempt 2/2)` |
| a sonda evitar a espera longa | saída completa em **5 s** — não os 30 s de um timeout nem os 60 s de dois |
| **não** cair na tela de crash | volta para a `MainActivity`; nenhum `GameCrashActivity` no log |
| avisar o usuário | `W GameLaunchTaskHandler: Game exited without persisting its saves` + toast *"O jogo não conseguiu salvar antes de fechar. O progresso recente pode ter sido perdido."* |
| a gravação ter falhado de verdade | o `.srm` **não** foi reescrito — mesmo mtime de antes do teste |

A última linha é o que impede o teste de passar por acidente: sem ela, um `saveOnExit` que
silenciosamente gravasse a SRAM daria o mesmo "voltou sem crashar".

O toast só aparece por ~2 s, então a captura tem que ser em rajada logo após o BACK — numa
tentativa anterior a captura saiu 8 s depois e o toast já tinha sumido, o que parecia falha do
código e não do roteiro.

**Depois do teste:** `fault_inject.py revert` (diff contra a cópia limpa: vazio), APK limpo
reinstalado. Instrumentação esquecida num build de distribuição é o pitfall 8.

## Recorrência em produção (telemetria, 2026-09-02)

O fix **está no build distribuído e está funcionando como projetado** — mas a causa de fundo
continua: a GLThread realmente trava, e agora o que se vê é o timeout de 30 s disparando.

- **Errors (serviço):** 3810, 3359, 3358 — `retrogamesystem/game`,
  `SourceFile::com.swordfish.libretrodroid.GLRetroView.runOnGLThread`
- **Versões:** app **1.17.11 e 1.17.12** — ou seja, **depois** do rebuild do AAR
- **Datas:** 2026-08-31 11:41, 2026-09-02 04:50
- **Aparelhos:** Samsung SM-A515F (Android 13) e outro Android 16 — `system=gc; core=dolphin;
  game=Need for Speed - Underground 2`

```
com.swordfish.libretrodroid.GLRetroView$GLThreadTimeoutException: GLThread did not answer in 30000 ms
	at com.swordfish.libretrodroid.GLRetroView.runOnGLThread(SourceFile:81)
	at com.swordfish.libretrodroid.GLRetroView.serializeSRAM(SourceFile:3)
	at j4.d$k.invokeSuspend(SourceFile:13)
	...
	Suppressed: T6.i: [T0{Cancelling}@486ab15, Dispatchers.Main.immediate]
```

Leitura:

1. **O call-site é o `serializeSRAM` da saída do jogo** — a segunda linha da tabela "Mesma
   classe de bug em outros dois pontos"
   ([BaseGameScreenViewModel.kt:289-299](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/BaseGameScreenViewModel.kt#L289-L299)).
   O `Suppressed: … Dispatchers.Main.immediate` mostra que o escopo cancelado é o Main, então o
   `withContext(IO)` aplicado nesse ponto está de fato tirando a main da espera — **o ANR virou
   crash de timeout**, que é uma troca boa mas não é a solução.
2. **A GLThread ficou 30 s sem drenar a fila.** Isso não é lentidão de `dlopen`: é a GLThread
   parada dentro de um callback do renderer do Dolphin, no fim da partida. Três das quatro
   ocorrências são `core=dolphin`.
3. Provável interação com [[2026-09-02-libretrodroid-dlclose-core-anterior-sigabrt]]: se a
   partida anterior deixa estado sujo, o `serializeSRAM` da saída pode estar competindo com a
   destruição do core.

**Pendência que isso adiciona:** hoje o timeout **lança** e o usuário perde o save da SRAM sem
aviso. Precisa de tratamento no chamador — no mínimo não derrubar a saída do jogo por causa
disso, e idealmente tentar de novo antes de desistir.

## O `dlopen` saiu da main thread (2026-09-10)

Último item nosso da lista de pendências. `LibretroDroid.create` faz `dlopen` do `.so` do core
(14 MB no Dolphin) e `retro_init`; rodava na main porque o `ON_CREATE` do `GLRetroView` é
despachado **sincronamente** pelo `addObserver`, que acontece dentro do `factory` do `AndroidView`.

### A correção: `create` como primeiro evento da fila da GLThread

```kotlin
// GLRetroView.onCreate — main thread
val refreshRate = getDefaultRefreshRate()   // dependem de serviços do sistema, custam ~nada
val language = getDeviceLanguage()
queueEvent { createCore(refreshRate, language) }
```

A ordenação não depende de sorte, e é isso que torna a mudança segura:

- a GLThread nasce no `setRenderer`, chamado no `init` da própria view — ela existe antes de
  qualquer `queueEvent`;
- `GLSurfaceView.guardedRun` **drena a fila inteira antes** de tratar pausa, superfície ou
  desenho ([GLSurfaceView.java:1330-1333, 1501-1505](https://cs.android.com/) — conferido no fonte
  do SDK 35, `sources/android-35`), inclusive sem superfície e inclusive pausado;
- logo, o `create` enfileirado no `ON_CREATE` roda **antes** de `onSurfaceCreated → initializeCore()`,
  que é quem chama `retro_load_game`. Há ainda um guard explícito: `initializeCore` desiste com log
  se o core não tiver sido criado, em vez de chamar `retro_get_system_info` em ponteiro nulo.

De quebra, `retro_init` passa a rodar na **mesma thread** de `retro_load_game` e `retro_run` — que é
o que o RetroArch faz, e portanto o que os cores esperam.

### As corridas que isso abre, e como cada uma foi fechada

Mover o `create` de thread quebra a premissa antiga de que "quando a view existe, o core existe".

| Risco | Tratamento |
|---|---|
| `destroy` (main, `ON_DESTROY`) chegar antes ou **durante** o `create` | Máquina de estados sob `coreLock`: quem trabalha na GLThread abre com `beginCoreWork()` e fecha com `endCoreWork()`; se o `ON_DESTROY` cair no meio, a destruição é **adiada** e executada pela própria GLThread ao terminar. A main nunca espera — esperar seria devolver a ela o bloqueio que este patch tirou. |
| Setters vindos da main (`audioEnabled`, `frameSpeed`, `shader`, `setControllerType`, `updateVariables`) | Viraram `queueEvent`. Como a fila é FIFO e o `create` é o primeiro evento, a ordem antiga ("depois do create") é preservada — sem isso o `create` sobrescreveria (ele zera `audioEnabled`/`frameSpeed`) ou haveria escrita concorrente no `Environment`. Efeito colateral bom: `retro_set_controller_port_device` e `updateVariable` deixam de rodar concorrentes ao `retro_run`. |
| Leituras da main (`getVariables`, `getControllers`) | Devolvem vazio enquanto o core não existe, em vez de ler containers que o `create` está preenchendo. Na prática os chamadores já esperam o 1º frame; o menu abriria sem as opções do core em vez de arriscar. |
| Exceção dentro de evento enfileirado | `setControllerType` **ignora** em vez de exigir: exceção lançada dentro de `queueEvent` sobe na GLThread, onde ninguém a captura, e mata o processo. Só as chamadas bloqueantes (`runOnGLThread`) podem lançar — lá a exceção é devolvida ao chamador. |

`printRetroVariables` (só em debug) passou a esperar o primeiro frame: antes lia as variáveis 1 s
depois de criar a view, e agora isso pode ser antes de o core existir.

### Medido em device

**Moto G86 5G, Android 16, GameCube/Dolphin, *Need for Speed - Underground 2*** — mesmo aparelho,
jogo e core das validações anteriores e da telemetria:

```
I GLRetroView: Core created on GLThread 2707 in 124 ms      <- tid 3195
I ...        : (processo :game, pid 3060 = tid da main)
```

A prova de que saiu da main é o **nome da thread no log**, não o tempo: tid 3195 ≠ pid 3060. O
`LOGD` nativo está compilado fora (`VERBOSE_LOGGING false` em `log.h`), então esse log em Kotlin é
permanente e é ele que responde "em que thread e em quanto tempo" num report futuro.

Durações medidas: **124 ms** com cache frio, 10-77 ms nas aberturas seguintes. Nesse aparelho o
custo é pequeno; o ganho é proporcional ao aparelho lento e ao core grande, que é onde o ANR nasce.

Na sessão limpa, durante a abertura do jogo, o logcat não trouxe **nenhum** `Choreographer: Skipped
frames` nem `InputDispatcher: spent …ms processing KeyEvent` para o processo `:game`.

### O que isto **não** resolve — e a medida que prova

Com uma pausa artificial de 8 s dentro do `create` (injeção, ver abaixo), a main do `:game` levou
**366 frames pulados** e um `KeyEvent` de **4,3 s**. Ou seja: tirar a nossa chamada da main não
imuniza a main enquanto a GLThread estiver longamente ocupada **antes do primeiro frame** — o
handshake do próprio `GLSurfaceView` (criação/redimensionamento de superfície) espera a GLThread
sem timeout. Isso é [[2026-09-03-anr-glsurfaceview-onpause-surfacechanged]], e esta medida
acrescenta um gatilho àquela página: além de `onPause` e resize durante a partida, o mesmo bloqueio
acontece **na abertura**, enquanto a ROM carrega.

## Bug novo, achado na validação: SIGSEGV ao sair antes do primeiro frame

A injeção de falha (pausa de 8 s na GLThread, para exercitar a destruição adiada) escancarou uma
janela que **já existia** e não tem relação com a mudança acima: sair do jogo — ou pedir um save
pelo menu — antes de o core rodar o primeiro frame matava o processo.

```
F libc   : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x14 in tid 9484 (GLThread 2710)
    #05  dolphin_libretro_android.so (retro_serialize_size+124)
    #06  liblibretrodroid.so (libretrodroid::LibretroDroid::serializeState()+24)
    #07  liblibretrodroid.so (Java_com_swordfish_libretrodroid_LibretroDroid_serializeState+44)
```

**Por que a janela é larga:** `createRetroView` marca `GameState.Ready` assim que constrói a view —
muito antes de a GLThread carregar a ROM. Numa ISO de GameCube isso são segundos, exatamente o
intervalo em que o usuário vê a tela preta com o pad desenhado (o sintoma que abre esta página) e
desiste. O `saveOnExit` então serializa contra um core sem jogo.

**E `retro_load_game` ter retornado não basta.** Na primeira tentativa a guarda usava "ROM
carregada" e o crash voltou: o Dolphin termina o boot numa thread própria (o `Booting from disc` do
core sai *depois* do `Starting game with fps`), então ainda não há estado a serializar. O critério
correto é **o primeiro frame emulado** — que é, aliás, o mesmo que o app já usava para *restaurar*
autosave ("PPSSPP and Mupen64 initialize some state while rendering the first frame").

**Correção:**

- [GLRetroView.kt] `hasRenderedFrame` (marcado no `onDrawFrame`) e `requireGameRunning()` guardando
  `serializeState`, `unserializeState`, `serializeSRAM`, `unserializeSRAM`, `reset` e `setCheat`.
  Falha com `GLRetroView.GameNotLoadedException`, checada **dentro** do bloco da GLThread e devolvida
  ao chamador pelo `runOnGLThread`.
- [GameViewModelSaves.kt] trata essa exceção como **"não há o que salvar"**: retorna sucesso, sem
  toast de alarme e sem telemetria — um jogo que nunca rodou não tem progresso a perder. Dizer
  "não conseguiu salvar" ali seria alarme falso.
- [GameViewModelSaves.kt] `saveSlot`, `saveQuickSave` e `loadQuickSave` deixaram de propagar exceção:
  o menu abre sobre a tela preta, então dá para pedir um save antes de existir estado, e a exceção
  subia do `launch` do chamador até o `UncaughtExceptionHandler` — tela de crash. Agora vira toast,
  com texto próprio para "ainda carregando" (`game_toast_state_while_loading`, em `values/` e
  `values-pt-rBR/`).

## Validação em device (2026-09-10)

Moto G86 5G, Android 16, app debug `arm64-v8a`, GameCube/Dolphin.

| O que | Observado |
|---|---|
| `create` fora da main | `Core created on GLThread 2707 in 124 ms`, tid ≠ pid; 10-77 ms nas demais |
| main não trava na abertura | nenhum `Skipped frames` / `spent …ms processing KeyEvent` no `:game` |
| menu do jogo (lê `getVariables` + sonda de discos) | abre com todas as opções |
| Silenciar / Acelerar (setters que viraram fila) | `EMUFPS 60 → 119,5` ao ligar o Acelerar: a escrita da main chegou ao core |
| salvar estado no slot | `…rvz.slot1` de **1,8 MB** gravado |
| carregar estado | queda de FPS no `unserialize` (`EMUFPS 17,6`) e volta a 60 |
| sair pelo menu | `Stored sram` + `Stored autosave file with size: 90108266` + `System.exit(0)`, sem crash |
| sair **durante** a carga (5 execuções) | `Nothing to save: the game never rendered a frame` (e o mesmo para o autosave), **zero SIGSEGV** — o mesmo roteiro crashava 2 em 4 antes da guarda |

**Método da injeção de falha** (a travada real não reproduz sob demanda, como esta página já
registra): `Thread.sleep(8000)` no início do `createCore`, AAR e APK temporários, teste, e depois
remoção + conferência (`grep` por `FAULT-INJECT`/`Thread.sleep` vazio) e rebuild limpo. Instrumentação
esquecida num build de distribuição é o pitfall 8.

**Builds:** `:libretrodroid:assembleRelease` e `:lemuroid-app:assembleFreeBundleDebug` sem warning
novo; `assembleFreeBundleRelease` **BUILD SUCCESSFUL** com `lintVital` e R8 (arm64-v8a 112 MB,
armeabi-v7a 91 MB). O teste em aparelho foi no APK **debug** — o release só foi compilado, como nas
validações anteriores desta página.

**Empacotamento:** AAR rebuildado do checkout `C:\projects\lemuroid\LibretroDroid-patched`, SHA-1
`e656e1008d560333c2bb398d288ed69c383ba9c0`, conferido **por classe** (`GLRetroView$createCore`,
`$destroyCore`, `$GameNotLoadedException` presentes no `classes.jar`), não por hash.

**Não observado:** o ramo de **destruição adiada** (`Core is busy; deferring destroy to the GL
thread`) não apareceu em nenhuma execução. Motivo entendido: para o `ON_DESTROY` chegar durante o
trabalho da GLThread, a main precisaria estar livre — e ela mesma fica presa no handshake de
superfície enquanto a GLThread está ocupada. O ramo existe como rede de segurança (e impede o
`destroy` com core nulo, que é deref garantido); o que se comprovou é o resultado: cinco saídas
durante a carga, nenhuma queda de processo.

> ⚠️ Achado de empacotamento no caminho: o AAR anterior tinha `arm64-v8a` compilado do fonte atual e
> **as outras três ABIs de um estado mais velho** (faltava a string de diagnóstico `VIDEOFRAMES`).
> Nenhuma correção de comportamento estava faltando — `llvm-nm -D` confirma ausência de `dlclose` nas
> quatro, e `EMUFPS` está nas quatro —, mas o drift entre ABIs é exatamente o que o pitfall 6 descreve.
> Agora as quatro saíram do mesmo build. Conferir isso é barato: `llvm-strings` numa string que só
> existe na versão nova.

## Lição

`GLSurfaceView.queueEvent` + `CountDownLatch.await()` sem timeout é um bloqueio de duração
**ilimitada** — a GLThread pode estar dentro de um callback do renderer que demora o que o
core quiser. Nenhuma chamada `runOnGLThread` pode partir da main thread.

E o corolário que a telemetria acrescentou: **timeout não é correção, é contenção**. Trocar
"trava para sempre" por "lança depois de 30 s" tira o ANR do caminho, mas o trabalho (salvar a
SRAM) continua não acontecendo — quem introduz um timeout tem que decidir também o que fazer
quando ele estoura.

Os dois de 2026-09-10:

**"A view existe" não é "o core existe", e "a ROM carregou" não é "o jogo está rodando".** O app
marca o jogo como pronto ao criar a `GLRetroView`; o core só nasce e carrega a ROM depois, na
GLThread, e o Dolphin ainda termina o boot numa thread dele. Toda chamada que dependa do estado
emulado precisa de um critério explícito, e o único que vale para os três cores é **o primeiro frame
emulado** — o mesmo que o app já usava para restaurar autosave, e que agora vale também para gravar.

**Tirar uma chamada da main thread só resolve a parte que é nossa.** Medido: com a GLThread ocupada
8 s antes do primeiro frame, a main pula 366 frames mesmo sem nenhuma chamada nossa — o
`GLSurfaceView` a bloqueia sozinho, no handshake de superfície. Ao mover trabalho para outra thread,
verificar quem mais espera por ela.

Ver também [2026-08-09-telemetria-nao-captura-anr.md](2026-08-09-telemetria-nao-captura-anr.md).

## Recorrência (Triagem 2026-09-28)

- **Novo ID:** 8622 — `com.swordfish.libretrodroid.GLRetroView$GLThreadTimeoutException: GLThread
  did not answer in 30000 ms`, call-site `GLRetroView.serializeSRAM` (o mesmo ponto documentado
  acima em "O call-site é o `serializeSRAM` da saída do jogo").
- **Contexto:** `phase=exit-save; call=serializeSRAM; system=nes; game=Mario Bros.`, Samsung
  SM-A166M, Android 16, `app=1.17.22`. Ocorrência única (não é cluster).
- **Diagnóstico:** confirma que o critério de fechamento desta página ("telemetria parar de
  acusar `GLThreadTimeoutException` em `runOnGLThread`") continua não satisfeito — mantido
  `open`. Diferente da maior parte dos casos anteriores (Dolphin/GameCube dominando a lista),
  este é **NES/nes9x**, um core leve — enfraquece a hipótese de "core pesado demora para
  responder" como explicação única e é consistente com a leitura já registrada acima: a causa
  de fundo é o core (ou o driver) ocasionalmente não devolver o controle à GLThread, não um
  problema de desempenho específico de um sistema.
- Sem log adicional além da stack Java (é `runOnGLThread`, não crash nativo — não há tombstone).
  Não reproduzido.

## Onde a GLThread está presa? — a telemetria não dizia (2026-10-02)

As quatro ocorrências (3358, 3359, 3810, 8622) trazem só a pilha de **quem espera**
(`runOnGLThread ← serializeSRAM ← saveOnExit`), que é sempre a mesma e não aponta causa. Sem saber
em que ponto a GLThread parou, a causa de fundo não tem como ser corrigida — e ela não reproduz sob
demanda. Esta rodada fecha esse buraco de diagnóstico.

### Símbolos abertos e o que se descobriu

- `GLSurfaceView.GLThread.guardedRun` (patchado, `LibretroDroid-patched`): a fila é drenada **antes**
  de pausa/superfície/desenho, um evento por volta, sob `mLock`. `mShouldExit` é testado **antes**
  da fila: thread encerrada descarta em silêncio o que estiver enfileirado, e `queueEvent` depois
  disso só faz `add` numa lista que ninguém lê — o chamador paga os 30 s inteiros.
- `GLThread.queueEvent` entra em `synchronized(mLock)`. Logo, um timeout (e não um travamento
  eterno de quem chama) prova que a GLThread **não** estava segurando `mLock`: ela estava fora do
  bloco — em `event.run()`, `createSurface`, `onSurfaceCreated` (carga da ROM), `onSurfaceChanged`,
  `onDrawFrame` (`LibretroDroid.step` → `retro_run`) ou `eglSwapBuffers` — ou nem existia mais.
- `onDetachedFromWindow → requestExitAndWait` é o único caminho para `mShouldExit` com a view viva
  (fora o `finalize`). Na saída pelo BACK/menu a view segue anexada: o `AndroidView` de
  `MobileGameScreen`/`TVGameScreen` não sai da composição durante o `loadingState`, e o
  `GameActivity` declara todo `configChanges` (não há recriação). Então, para `phase=exit-save`,
  "thread encerrada" é improvável — mas é barato confirmar, e é o que o dump abaixo faz.
- Nativo: `LibretroDroid::step` limita a 2 frames por volta e `FPSSync::wait` dorme no máximo um
  intervalo de frame; `CoreWorkGuard.begin/end` só pegam um lock curto, nunca esperam. **Nada nosso
  segura a GLThread por 30 s** — sobra core ou driver.
- `TelemetryReporter.reportThrowable` já aceita `extraLog` (vira um segundo item de `logs`).

### Hipóteses descartadas

- **Lentidão de core pesado**: a 8622 é NES (core leve) — já registrado acima.
- **Detach da view antes da gravação de saída**: ver acima; sem caminho conhecido no fluxo de saída.
- **Lost wakeup no `GLSurfaceView` patchado**: `queueEvent` faz `add` + `notifyAll` sob o mesmo
  lock em que a GLThread testa a fila antes do `wait()`. Não há janela.
- **Probe antes da primeira tentativa de `serializeSRAM` para encurtar a saída**: tiraria os 30 s
  da saída com jogo travado, mas trocaria SRAM que talvez ainda fosse gravada por velocidade — sem
  dado de quantas vezes a GLThread volta depois de 2 s, não há base para essa troca. Fica para
  depois que o dump disser onde ela para.

### O que foi feito

- `GLThreadDump` (app, `shared/game`): no timeout, lista as threads vivas `GLThread *` do processo
  com estado e pilha — ou registra que não há nenhuma (thread encerrada: os eventos nunca rodariam).
- O dump vai como `extraLog` nos três pontos que reportam `GLThreadTimeoutException`:
  `GameViewModelSaves.reportSaveFailure` (`exit-save`/`background-save`), `BaseGameActivity.readDiskState`
  (`open-menu`) e o `UncaughtExceptionHandler` do `BaseGameActivity` (núcleo travado, terminal).
- Teste JVM `GLThreadDumpTest`: thread `GLThread <n>` parada num método conhecido aparece no dump
  com o método; sem thread, o dump diz que não há GLThread viva.

**Como ler o próximo report:** o topo da pilha da GLThread decide o dono — `LibretroDroid.step`
(core: `retro_run` não volta), `eglSwapBuffers`/`EglHelper.swap` (driver/BufferQueue),
`LibretroDroid.loadGameFromPath`/`onSurfaceCreated` (carga da ROM ainda em curso), `Object.wait`
dentro de `guardedRun` (a GLThread **está ociosa** e não viu o evento — bug nosso no
`GLSurfaceView`), ou "no live GLThread" (thread encerrada antes da gravação — bug nosso no ciclo de
vida).

### Validação (2026-10-02)

- `./gradlew :lemuroid-app:testFreeBundleDebugUnitTest` → 49 testes, 0 falhas (inclui os 3 de
  `GLThreadDumpTest`; o primeiro só passa se o dump trouxer o **método** em que a thread está parada,
  não só o nome dela).
- `./gradlew :lemuroid-app:ktlintCheck` → passa. O baseline do `lemuroid-app` foi regenerado só porque
  as duas linhas inseridas no `BaseGameActivity` deslocaram 21 entradas já congeladas (mesmas regras e
  colunas, linha +1/+2); o diff do `baseline.xml` não tem apontamento novo.
- Release: `-keep class com.swordfish.libretrodroid.** { *; }` (`proguard-rules.pro:91`) mantém
  `GLSurfaceView$GLThread`, `GLRetroView$Renderer` e `LibretroDroid` legíveis no dump; frames do app,
  se aparecerem, precisam do `mapping.txt` da versão.
- **Não testado em device**: a travada real não reproduz sob demanda, e o que mudou é só o conteúdo do
  report (o fluxo de saída/menu é o mesmo validado em 2026-09-03).

### Próximo passo

Esperar o próximo `GLThreadTimeoutException` na telemetria (app ≥ a versão que levar este commit) e
ler o segundo item de `logs` — o dump. O topo da pilha da GLThread escolhe o caminho, conforme a
tabela de "Como ler o próximo report" acima. O critério de fechamento desta página não muda.

## Passagem de bastão — testes pendentes (2026-10-02)

> ✅ **Executada** na sessão seguinte do mesmo dia: Teste 1 e Teste 2 feitos (resultados abaixo),
> commits `46b4995` (código) e `6a4eef3` (doc). A passagem **vigente** é a última seção da página.

Estado ao fim da sessão: código do `GLThreadDump` pronto, testes unitários e ktlint verdes,
**nada commitado**. Arquivos desta rodada (a árvore tem outras mudanças pendentes de trabalhos
anteriores — commitar só estes):

- `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/GLThreadDump.kt` (novo)
- `lemuroid-app/src/test/java/com/swordfish/lemuroid/app/shared/game/GLThreadDumpTest.kt` (novo)
- `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/viewmodel/GameViewModelSaves.kt`
  (`reportSaveFailure` → `extraLog = GLThreadDump.forFailure(error)`)
- `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/BaseGameActivity.kt`
  (`readDiskState` e `setUpExceptionsHandler`)
- `lemuroid-app/config/ktlint/baseline.xml` (só deslocamento de linha do `BaseGameActivity`;
  o arquivo já tinha mudanças pendentes de antes — conferir o hunk ao fazer `git add -p`)
- esta página

Não é preciso reler o código da análise: as conclusões estão nas seções "Onde a GLThread está
presa?" e "Validação (2026-10-02)" acima.

### Teste 1 — telemetria: há padrão nas ocorrências? (precisa de permissão)

Nesta sessão a consulta não rodou: sem `JWT_SECRET_KEY`/`TRIAGEM_TOKEN` no ambiente, e o modo auto
barra a leitura do `.env`. Com o modo **edit** e a permissão aceita, seguir a skill
`triagem-bugs-prod` (`~/.claude/skills/triagem-bugs-prod/SKILL.md`, seção "Ambiente" — injeta
`JWT_SECRET_KEY` a partir de `C:\projects\digitalstoregamesproject\digitalstoregamesbackend\.env`):

```
python ~/.claude/skills/triagem-bugs-prod/scripts/triagem.py list --project retrogamesystem --json <scratch>/errs.json
```

Filtrar **todos os status** (não só `open`) por `GLThreadTimeoutException` e tabular, a partir do
`page_url` (contexto): `phase=` (`exit-save` / `background-save` / `open-menu` / terminal),
`system=`, `device=`, `app=`. Perguntas que a tabela responde:

1. Aparece `phase=background-save`? Se sim, reabrir a hipótese "view desanexada → GLThread encerrada
   → eventos descartados" (activity destruída com o `:game` em segundo plano) — ver
   `GLSurfaceView.onDetachedFromWindow`/`requestExitAndWait` no `LibretroDroid-patched`.
2. Concentra em fabricante/driver (Samsung/Mali?) ou em core? Driver aponta para `eglSwapBuffers`.
3. Já há report com o 2º item de `logs` (o dump)? Só a partir da versão que levar este commit.

**Não fechar** esses erros no painel por esta rodada: a página continua aberta e eles são o
critério de fechamento. Registrar a tabela aqui, com o comando e os números.

#### Resultado do Teste 1 (2026-10-02)

O `triagem.py list` só devolve `status=open`; para todos os status foi usado um script de scratchpad
que importa o próprio `triagem.fetch_all` e filtra por `"GLThreadTimeout"` em qualquer campo:
**9.284 erros no serviço → 11 com `GLThreadTimeoutException`, todos já `close`, todos com
`logs_count: 1`**. Dois são `1.17.12-DEBUG` do Moto G86 (4193, 4219 — as injeções de 2026-09-03) e
saem da conta. A fase das versões anteriores à 1.17.19 (sem `phase=` no contexto) foi tirada do
call-site, 3ª linha da pilha (`triagem.py logs <ids> --out <scratch>`): `getAvailableDisks` =
`open-menu`.

| id | data | app | aparelho | Android | sistema | call-site | fase |
|----|------|-----|----------|---------|---------|-----------|------|
| 3358 | 08-31 11:41 | 1.17.11 | samsung SM-G990E | 16 | gc (dolphin) | `getAvailableDisks` | open-menu |
| 3359 | 08-31 11:43 | 1.17.11 | samsung SM-G990E | 16 | gc (dolphin) | `serializeSRAM` | gravação (sem `phase=`) |
| 3810 | 09-02 04:50 | 1.17.12 | samsung SM-A515F | 13 | gc (dolphin) | `serializeSRAM` | gravação (sem `phase=`) |
| 5947 | 09-11 17:45 | 1.17.12 | samsung SM-A515F | 13 | gc (dolphin) | `getAvailableDisks` | open-menu |
| 5948 | 09-11 17:46 | 1.17.12 | samsung SM-A515F | 13 | gc (dolphin) | `getAvailableDisks` | open-menu |
| 5965 | 09-11 19:16 | 1.17.12 | samsung SM-A515F | 13 | gc (dolphin) | `getAvailableDisks` | open-menu |
| 6283 | 09-13 16:42 | 1.17.19 | samsung SM-G985F | 13 | gc | `serializeSRAM` | exit-save |
| 7631 | 09-20 17:19 | 1.17.20 | Xiaomi 24095PCADG | 16 | neogeo | `serializeSRAM` | exit-save |
| 8622 | 09-25 21:57 | 1.17.22 | samsung SM-A166M | 16 | nes | `serializeSRAM` | exit-save |

Respostas:

1. **`phase=background-save`: nenhuma.** Das 5 gravações, 3 são `exit-save` explícito e 2 (3359,
   3810) são de antes do `phase=` — a classe ofuscada (`j4.d$k`) não diz se era saída ou segundo
   plano sem o `mapping.txt` daquela versão. A hipótese "view desanexada → GLThread encerrada" não
   ganha apoio; segue improvável.
2. **Concentração:**
   - **Core:** 6 de 9 são GameCube/Dolphin — mas 4 delas vêm de um único SM-A515F, todas em jogos
     *Need for Speed* (U, U2, Hot Pursuit 2), e duas vêm de um único SM-G990E na mesma sessão de
     jogo (menu às 11:41, saída às 11:43). São **5 aparelhos distintos** ao todo. Os 3 casos de
     ≥ 1.17.19 são 3 sistemas diferentes (gc, neogeo, nes).
   - **Fabricante:** Samsung em 7 de 9 reports / 4 de 5 aparelhos. Linha de base: Samsung é **59,1%**
     dos 4.012 reports `retrogamesystem/*` não-debug (mesma coleta). Com n = 5 aparelhos isso não
     separa de acaso — **não dá para apontar driver** (e a tela de `eglSwapBuffers` não fica mais nem
     menos provável).
   - Dado lateral: das 76 reports não-debug de `retrogamesystem/game`, 9 são `system=gc` — e 6 dessas
     9 são este timeout. No GameCube, este é o erro dominante.
3. **Report com o dump (2º item de `logs`): nenhum** — todos têm `logs_count: 1`. A última versão que
   reportou foi a 1.17.22; o `GLThreadDump` ainda não foi distribuído.

**Correção ao que está acima:** a seção "Recorrência em produção (telemetria, 2026-09-02)" põe 3358
como `serializeSRAM`; a pilha dela é `getAvailableDisks` (abrir o menu). E "As quatro ocorrências"
de "Onde a GLThread está presa?" eram, na verdade, nove em produção — 5947, 5948, 5965, 6283 e 7631
foram fechadas em triagens anteriores sem entrar nesta página. Nenhuma das duas correções muda a
conclusão: a pilha de quem espera continua sem apontar causa.

**Observação:** os 4 `open-menu` são todos ≤ 1.17.12; de 1.17.19 em diante só aparece `exit-save`.
Não investigado se é mudança de comportamento ou acaso de amostra pequena.

### Teste 2 — em aparelho: o dump chega à telemetria? (injeção de falha)

A travada real não reproduz sob demanda, então forçar uma — mesmo método das validações de
2026-09-03 e 2026-09-10. Aparelho: SM-A127M `RX8R90G1D6E` via adb (ou o Moto G86 se conectado).

1. Copiar `GameViewModelSaves.kt` limpo para o scratchpad.
2. Em `trySaveSRAM`, antes do `withContext(Dispatchers.IO) { view.serializeSRAM() }`, só na
   primeira tentativa, inserir com marcador:
   ```kotlin
   // FAULT-INJECT: prende a GLThread num evento por 40 s
   if (attempt == 0) view.queueEvent { Thread.sleep(40_000) }
   ```
   O `serializeSRAM` enfileirado atrás dele estoura os 30 s, a sonda de 2 s falha e cai em
   `reportSaveFailure` → `GLThreadDump`.
3. `./gradlew :lemuroid-app:assembleFreeBundleDebug`, instalar o APK `arm64-v8a`, abrir um jogo leve
   (SNES/NES), esperar o 1º frame, sair pelo BACK.
4. Conferir, nesta ordem:
   - logcat: `W GameViewModelSaves: SRAM save timed out (attempt 1/2)` e, ~32 s depois do BACK, a volta
     para a `MainActivity` com o toast de "não conseguiu salvar" — **sem** `GameCrashActivity`;
   - painel (Teste 1, `triagem.py logs <id>`): o report novo tem **dois** itens em `logs`, e o segundo
     começa com `GLThread <n> state=TIMED_WAITING` e traz `Thread.sleep` ← `GameViewModelSaves` ←
     `GLSurfaceView$GLThread.guardedRun`. Isso prova que o dump pega a thread certa no processo
     `:game` real (o teste JVM só prova com thread sintética).
   - A telemetria deduplica por mensagem **dentro do processo**: repetir só após matar o `:game`.
5. Reverter: `grep -rn "FAULT-INJECT" lemuroid-app/src` vazio **e** `diff` contra a cópia limpa
   vazio; rebuild e reinstalar o APK limpo. Instrumentação esquecida em build de distribuição é o
   pitfall 8. Fechar no painel **só** o erro gerado pela injeção.

#### Resultado do Teste 2 (2026-10-02) — passou

SM-A127M `RX8R90G1D6E` (Android 13), `assembleFreeBundleDebug` com a injeção acima (app
`1.17.23-DEBUG`), APK `arm64-v8a`. Jogo: *Super Mario Bros. 3 (EU)*, NES — baixado pelo próprio app
(o aparelho não tinha ROM). Primeiro frame visível, depois BACK.

Linha do tempo (logcat `-v time`, relógio do aparelho):

| hora | evento |
|------|--------|
| 18:45:24.140 | `Displayed …GameActivity: +3s607ms` |
| 18:45:43.603 | BACK (imagem congela com spinner — GLThread no `sleep`) |
| 18:46:13.758 | `W GameViewModelSaves: SRAM save timed out (attempt 1/2)` — **+30,15 s** |
| 18:46:17.788 | `W GameViewModelSaves: Skipping autosave (exit-save): GL thread is not draining its event queue` |
| 18:46:18.291 | `Process app.retrogamesystem.debug:game (pid 30990) has died` |
| 18:46:18.435 | toast via `SafeToastKt.showToastSafely`, no processo principal (pid 30090) |

Sem `GameCrashActivity`: volta para a `MainActivity` com o toast. A saída levou **34,7 s**, não os
~32 s previstos — são **duas** sondas de 2 s, uma no `trySaveSRAM` (decide se repete) e outra no
`persistSession` (decide se tenta o autosave).

Painel: report **9291** (`retrogamesystem/game`, `phase=exit-save; call=serializeSRAM; system=nes;
game=Super Mario Bros. 3`, `logs_count: 2`). `triagem.py logs 9291`, item `seq=1`:

```
GLThread 1 state=TIMED_WAITING
	at java.lang.Thread.sleep(Native Method)
	at java.lang.Thread.sleep0(Thread.java:689)
	at java.lang.Thread.sleep(Thread.java:667)
	at java.lang.Thread.sleep(Thread.java:580)
	at com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelSaves.trySaveSRAM$lambda$5$lambda$4(GameViewModelSaves.kt:151)
	at com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelSaves.$r8$lambda$ZTFw7N7MTx9WY6AVeYCyB9jT8h0(GameViewModelSaves.kt:0)
	at com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelSaves$$ExternalSyntheticLambda0.run(D8$$SyntheticClass:0)
	at com.swordfish.libretrodroid.GLSurfaceView$GLThread.guardedRun(GLSurfaceView.java:785)
	at com.swordfish.libretrodroid.GLSurfaceView$GLThread.run(GLSurfaceView.java:608)
```

Exatamente o previsto: o dump pega a GLThread **real** do processo `:game` e mostra em que evento
ela está presa (`guardedRun:785` = `event.run()`), não só o nome. Numa travada de verdade, o lugar
do `sleep` será ocupado por `LibretroDroid.step`, `eglSwapBuffers` etc.

**Risco observado (não corrigido):** o report é `terminal = false` — POST numa thread daemon. O
servidor registrou o 9291 às `21:46:17` UTC, ~1 s antes de o `:game` morrer (18:46:18.29 local). A
janela entre o `reportSaveFailure` e o `exitProcess` é só a das duas sondas (~4,5 s); numa rede lenta
o report — e o dump com ele — se perde. Se os próximos timeouts de produção chegarem sem o 2º item,
este é o primeiro suspeito.

Reversão: cópia limpa restaurada; `FAULT-INJECT` em `lemuroid-app/src` → 0 ocorrências; hash
SHA-256 do `GameViewModelSaves.kt` igual ao da cópia limpa. Rebuild e reinstalação do APK limpo; no
aparelho, BACK às 18:50:58 → `Stored sram file` 18:50:59.620 → `Stored autosave file with size:
13805` 18:50:59.736 → `:game` encerrado 18:51:00.244 (saída normal em ~2 s).

9291 fechado no painel (`triagem.py close 9291` → `affected=1`; `verify 9291` → `ainda abertos: 0`).

### Depois dos testes

- Registrar os resultados aqui (comandos literais, números, o trecho do dump).
- Commitar só os arquivos listados acima (mensagem sugerida:
  `diag(game): anexa a pilha da GLThread ao report de GLThreadTimeoutException`) — o bug segue em
  `open/`, então não mover para `done/`.
- Decisão em aberto (não tomar sem dado): sonda antes da 1ª tentativa de `serializeSRAM` para encurtar
  a saída com jogo travado de ~32 s para ~2 s — ver "Hipóteses descartadas".

## O que falta para `done/` (2026-10-02)

O critério (topo da página) é a telemetria parar de acusar o timeout — o que só acontece corrigindo
a causa de fundo, e ela ainda é desconhecida. Caminho:

1. **Garantir que o dump sobreviva ao fim do processo** (Task A abaixo) — senão o primeiro report
   real pode chegar sem o 2º item e a espera recomeça.
2. **Publicar uma versão com `46b4995` + Task A.** Hoje nenhum aparelho de produção manda o dump
   (último report: 1.17.22).
3. **Esperar timeouts de produção com o dump.** Frequência baixa: 9 em ~1 mês, 3 desde a 1.17.19 —
   pode levar semanas. Consultar com o script de "Resultado do Teste 1" (todos os status, filtro
   `GLThreadTimeout`) ou `triagem.py list --project retrogamesystem` (só abertos) e
   `triagem.py logs <id>`.
4. **Ler o topo da pilha da GLThread** ("Como ler o próximo report"):
   - `Object.wait` em `guardedRun` ou "no live GLThread" → **nosso** (`GLSurfaceView` patchado /
     ciclo de vida). Corrigir; o critério é alcançável.
   - `loadGameFromPath`/`onSurfaceCreated` → carga ainda em curso, ordem de eventos nossa —
     provavelmente corrigível.
   - `LibretroDroid.step` (`retro_run` não volta) ou `eglSwapBuffers` → core/driver, **fora do
     nosso alcance**.
5. **Se cair em core/driver: decisão do dono** — o critério atual nunca se cumpre. Alternativa:
   redefinir para "causas nossas descartadas pelos dumps + resto documentado como de terceiros",
   acompanhado da mitigação em aberto (sonda antes da 1ª tentativa de `serializeSRAM`: saída com
   jogo travado de ~35 s → ~2 s, ao custo de desistir de SRAM que talvez ainda gravasse — os dumps
   dão o dado para pesar isso).

## Passagem de bastão — Task A (2026-10-02)

> ✅ **Executada** na sessão seguinte do mesmo dia — ver "Resultado da Task A" abaixo. A passagem
> **vigente** é a última seção da página.

Estado: árvore limpa quanto a este bug (commits `46b4995`, `6a4eef3`; nada pendente desta página).
Fora do escopo e **não** commitar junto: `.claude/settings.json` e o resto do
`lemuroid-app/config/ktlint/baseline.xml` (troca de 3 entradas duplicadas do `Color.kt` entre
`src/debug` e `src/release` — artefato de regeneração, não desta rodada).

### Task A — o report não-terminal tem que terminar antes do `exitProcess`

**Problema (medido no Teste 2):** `reportSaveFailure` usa `terminal = false` → o POST roda numa
thread daemon e ninguém espera. O `:game` morreu ~4,5 s depois do report; o servidor registrou o
9291 só ~1 s antes. Rede lenta = report perdido junto com o dump.

**Símbolos abertos (2026-10-02):**

- `TelemetryReporter.report` (`shared/telemetry/TelemetryReporter.kt:102-156`): cria
  `Thread("Lemuroid-Telemetry")` daemon; só faz `worker.join(TERMINAL_JOIN_MS = 2500)` quando
  `terminal`. Não guarda referência ao worker nos não-terminais. Nunca lança (tudo em `try/catch`).
  Dedup por `component|message` no processo (`seen`).
- `BaseGameScreenViewModel.requestFinish` (`:308-325`): `saves.saveOnExit(game)` (nunca lança) →
  `sideEffects.requestSuccessfulFinish(savesFailed = !saved)`.
- `BaseGameActivity.finishAndExitProcess` (`:582-591`): `GlobalScope.launch { delay(animationDuration());
  exitProcess(0) }` e `finish()`. **É o ponto único por onde as saídas normais matam o `:game`.**
- `GameProcessSession` (`shared/game/GameProcessSession.kt:35-36`): o processo principal, antes de
  relançar um jogo, espera o `:game` sair por `EXIT_TIMEOUT_MS = 3000` e depois o mata. Conta com o
  `exitProcess` saindo **400 ms** após o `finish()`.

**O que fazer:**

1. `TelemetryReporter`: guardar os workers não-terminais em voo (conjunto sincronizado; remover ao
   terminar) e expor `awaitPending(timeoutMs: Long)` que faz `join` com prazo **total** (não por
   worker). Nunca lança.
2. `BaseGameActivity.finishAndExitProcess`: dentro do `GlobalScope.launch`, antes do `exitProcess`,
   chamar `TelemetryReporter.awaitPending(...)` (em `Dispatchers.IO` ou aceitando bloquear uma
   thread do Default — decidir lendo o código). Prazo: **delay + espera < 3000 ms**, senão o
   `GameProcessSession.awaitGameProcessExit` mata o processo antes (o report se perde do mesmo
   jeito e o relançamento fica mais lento). Sugestão: espera ≤ 2000 ms.
   - Atenção ao pitfall 13 do `CLAUDE.md`: o processo continua vivo durante a espera; não pode haver
     segunda sessão de core nesse intervalo — o `tryClaim` já recusa, mas conferir que nada novo
     depende do `exitProcess` sair em 400 ms.
3. Sem report pendente, a espera tem que ser zero (saída normal não pode ficar mais lenta).

**Testes que provam:**

- JVM (`lemuroid-app/src/test/.../telemetry/`): `awaitPending` volta na hora sem pendente; com um
  worker bloqueado, volta no prazo e não lança. Se o `send` não for injetável, isolar a parte de
  rastreio dos workers para testar sem rede.
- Aparelho: repetir **exatamente** o Teste 2 (injeção `FAULT-INJECT` no `trySaveSRAM`, mesmo
  procedimento de cópia limpa e reversão) com a rede limitada — por exemplo, um
  `Thread.sleep(3000)` temporário dentro do `send`, também marcado `FAULT-INJECT`. Sem a Task A o
  report some; com ela, chega com `logs_count: 2`. Conferir no logcat que a saída normal (sem
  injeção) continua em ~2 s do BACK ao `:game has died`.
- `./gradlew :lemuroid-app:testFreeBundleDebugUnitTest` e `:lemuroid-app:ktlintCheck` verdes;
  reverter as injeções (`grep -rn FAULT-INJECT lemuroid-app/src` vazio + hash igual à cópia limpa).
- Fechar no painel só os reports gerados pela injeção (`triagem.py close <id>` + `verify`).

**Depois:** registrar resultados aqui, commitar, e o passo seguinte é publicar a versão (passo 2 de
"O que falta para `done/`"). O bug continua em `open/`.

### Execução da Task A (2026-10-02)

**Análise antes da edição** (símbolos reabertos nesta sessão, além dos listados acima):

- `TelemetryReporter.report:139-152`: o worker é criado e iniciado na mesma linha; o `send` não é
  injetável (o objeto lê `Build.*` e `HttpURLConnection` direto). Por isso o rastreio dos workers vai
  para uma classe à parte, `TelemetryWorkers`, testável na JVM sem rede.
- `BaseGameActivity.finishAndExitProcess:582-591`: `GlobalScope.launch` sem dispatcher = `Default`.
  A espera é um `Thread.join` (bloqueante), então o `launch` passa a `Dispatchers.IO` — bloquear até
  2 s uma thread do `Default` competiria com as corrotinas da UI que ainda rodam no fade-out.
- `animationDuration()` (`retrograde-util/.../Android.kt:23`) = `config_mediumAnimTime`, 400 ms no
  AOSP mas valor de recurso do fabricante. O prazo do report é calculado como
  `EXIT_DEADLINE_MS (2400) − duração`, não fixo em 2000: delay + espera fica ≤ 2,4 s mesmo numa ROM
  que alongue a animação, abaixo dos 3 s do `GameProcessSession.EXIT_TIMEOUT_MS`.
- `setUpExceptionsHandler:198-217`: o report terminal já faz `join(2500)` e depois chama
  `performUnexpectedErrorFinish` → `finishAndExitProcess`. **Decisão:** rastrear **todos** os workers,
  não só os não-terminais — um terminal que estourou os 2,5 s ganha a mesma janela extra antes do
  `exitProcess`, e o código fica sem bifurcação. O orçamento de 3 s do processo principal começa a
  contar só quando o resultado chega (no `finish()`, depois do `join`), então não é afetado.
- Pitfall 13: o que depende do `:game` sair rápido é (a) `tryFallbackCore`, que já espera por
  `awaitGameProcessExit` (3 s, mata depois), e (b) um jogo novo aberto pelo usuário nessa janela, que
  cai no processo velho e é recusado por `tryClaim` → `RESULT_RESTART_IN_FRESH_PROCESS` →
  `killProcess`. Nenhum dos dois quebra com a janela maior; no pior caso o report é perdido, que é o
  contrato da telemetria. Sem report pendente, `awaitAll` volta na hora (a janela continua 400 ms).

**Tasks:**

1. `shared/telemetry/TelemetryWorkers.kt` (novo): `start(name, block)` registra o thread **antes** do
   `start()` (senão um worker rápido sai do conjunto antes de entrar) e se remove no `finally`;
   `awaitAll(timeoutMs)` faz `join` com prazo total; nunca lança. Teste:
   `TelemetryWorkersTest` (sem pendente volta na hora; worker preso → volta no prazo sem lançar;
   worker que termina → `pendingCount` volta a 0).
2. `TelemetryReporter.report`: worker via `TelemetryWorkers.start`; `awaitPending(timeoutMs)` público.
3. `BaseGameActivity.finishAndExitProcess`: `launch(Dispatchers.IO)` → `delay` →
   `TelemetryReporter.awaitPending(EXIT_DEADLINE_MS − duração)` → `exitProcess(0)`.
4. Aparelho: Teste 2 com `Thread.sleep(3000)` extra no `send` (as duas injeções `FAULT-INJECT`).

#### Resultado da Task A (2026-10-02) — passou

**Código:** `TelemetryWorkers` (novo), `TelemetryReporter.report` → `workers.start(...)` +
`awaitPending`, `BaseGameActivity.finishAndExitProcess` (`launch(Dispatchers.IO)` → `delay` →
`awaitPending(EXIT_DEADLINE_MS − duração)` → `exitProcess`), `EXIT_DEADLINE_MS = 2_400L`. Comentários
de prazo em `GameProcessSession` e `GameLaunchTaskHandler.tryFallbackCore` (diziam "400 ms").

**JVM:** `./gradlew :lemuroid-app:testFreeBundleDebugUnitTest` → **54 testes, 0 falhas** (49 + 5 de
`TelemetryWorkersTest`: sem pendente volta em < 100 ms — medido 2 ms; dois workers presos com prazo de
300 ms → volta em 250–1000 ms, medido 309 ms, prazo total e não por worker; worker que termina em 200 ms
→ esperado e `pendingCount` 0; worker que lança → sai do conjunto; thread interrompido → não lança e
mantém a flag). `:lemuroid-app:ktlintCheck` → passa. As 18 entradas do `baseline.xml` do
`TelemetryReporter` (+12 linhas) e as 2 do `BaseGameActivity` (+7) foram **deslocadas à mão** — mesma
regra e coluna — em vez de regenerar o arquivo, que tem mudanças pendentes alheias (as do `Color.kt`).

**Aparelho** (SM-A127M `RX8R90G1D6E`, Android 13, `1.17.23-DEBUG` `arm64-v8a`, *Super Mario Bros. 3
(EU)*, BACK depois do 1º frame). Injeções `FAULT-INJECT`: a do Teste 2 no `trySaveSRAM` +
`Thread.sleep(2_000)` no início do `TelemetryReporter.send`. **2 s e não os 3 s sugeridos:** no Teste 2
o report saiu ~2,5 s antes da morte do `:game` e a rede levou ~1,5 s; com 2 s, sem a Task A o envio
termina ~1 s depois da morte, e com ela ~1 s antes do teto de 2,4 s — margem dos dois lados.

| build | report (≈ timeout + sonda) | `finish` (≈ "Skipping autosave") | `:game has died` | painel |
|-------|------|------|------|------|
| **controle** (+ 3ª injeção: `awaitPending(0L)`) | 22:26:47,6 | 22:26:49,66 | 22:26:50,16 (+0,5 s) | **nada** — nenhum report do SM-A127M |
| **Task A** | 22:31:25,1 | 22:31:27,13 | 22:31:28,21 (+1,1 s) | **9311**, `01:31:28` UTC, `logs_count: 2` |

Com a Task A a espera acabou assim que o envio terminou (+1,1 s, abaixo do teto de 2,4 s), e o toast de
"não salvou" apareceu às 22:31:27,71, **antes** da morte do `:game` — o resultado chega no `finish()`,
então a espera não atrasa nada que o usuário veja. Sem `GameCrashActivity` nos dois builds.
`triagem.py logs 9311`, `seq=1`: `GLThread 1 state=TIMED_WAITING` → `Thread.sleep` ←
`GameViewModelSaves.trySaveSRAM…(GameViewModelSaves.kt:151)` ← `GLSurfaceView$GLThread.guardedRun(:785)`
— o mesmo dump do Teste 2.

**Saída normal** (APK limpo): BACK ~22:34:50,5 → `Stored sram` 50,623 → `Stored autosave` 50,813 →
`:game has died` 51,433. 620 ms do último save à morte (400 ms de animação + overhead): sem report
pendente, a espera é zero.

**Reversão:** `grep -rn FAULT-INJECT lemuroid-app/src` → 0; `sha256sum -c` dos 3 arquivos contra a cópia
limpa → OK; rebuild e reinstalação do APK limpo (a saída normal acima é dele). Painel: `close 9311` →
`affected=1`; `verify 9311` → `ainda abertos: 0`.

#### Ocorrência nova em produção: 9292 (achada nesta rodada, **não** fechada)

`retrogamesystem/game`, 2026-10-02 21:48 UTC, **Anbernic RG557** (Android 14), app **1.17.23**,
`phase=exit-save; call=serializeSRAM; system=gc`, *Capcom vs. SNK 2 EO*, `logs_count: 1`. É a 10ª em
produção e a primeira fora de Samsung/Xiaomi — de novo GameCube. Sem dump porque a 1.17.23 foi
fechada em `bc9c343` (18:00), **antes** do `46b4995` (21:28): `git log -S'versionName = "1.17.23"'`
→ `bc9c343`. Fica aberta: os reports desta família são o critério de fechamento da página.

> ⚠️ **A versão que levar o dump precisa ser 1.17.24 ou maior.** A 1.17.23 já existe em produção
> **sem** o `GLThreadDump`; publicar o dump com o mesmo `versionName` torna impossível saber, pelo
> `app=` do report, se a ausência do 2º item é "versão sem dump" ou "dump perdido".

## Passagem de bastão — publicar e esperar (2026-10-02, vigente)

Task A feita e commitada (esta seção substitui a anterior como vigente). Próximo passo é o 2 de
"O que falta para `done/`": publicar uma versão **≥ 1.17.24** com `46b4995` + Task A. Depois, consultar
`triagem.py list --project retrogamesystem` filtrando `GLThreadTimeout` e ler o `seq=1` de cada report
com `app=` ≥ 1.17.24 conforme "Como ler o próximo report". O 9292 continua aberto no painel.
