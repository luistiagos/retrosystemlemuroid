# [BUG] ANR ao inicializar jogo — main thread bloqueia em `runOnGLThread` enquanto o core carrega a ROM

**Data:** 2026-08-09
**Status:** 🟡 Corrigido em três pontos e validado em device (2026-09-03, GameCube/Dolphin) — segue **aberto** porque a causa de fundo (GLThread travando dentro do core) não é nossa e não reproduz sob demanda; `dlopen` na main segue como resíduo conhecido
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

## Lição

`GLSurfaceView.queueEvent` + `CountDownLatch.await()` sem timeout é um bloqueio de duração
**ilimitada** — a GLThread pode estar dentro de um callback do renderer que demora o que o
core quiser. Nenhuma chamada `runOnGLThread` pode partir da main thread.

E o corolário que a telemetria acrescentou: **timeout não é correção, é contenção**. Trocar
"trava para sempre" por "lança depois de 30 s" tira o ANR do caminho, mas o trabalho (salvar a
SRAM) continua não acontecendo — quem introduz um timeout tem que decidir também o que fazer
quando ele estoura.

Ver também [2026-08-09-telemetria-nao-captura-anr.md](2026-08-09-telemetria-nao-captura-anr.md).
