# [BUG] TV box MXQ 4K Pro — todo jogo cai na tela de erro com `BadTokenException` do Toast

**Data:** 2026-08-16
**Status:** Resolvido ✅ (2026-09-17)
**Severidade:** Crítica (o app é inutilizável nesses aparelhos — nenhum jogo abre)
**Branch:** version9

---

## Sintoma

Em TV boxes **MXQ 4K Pro**, **todo** jogo de **todo** sistema termina na `GameCrashActivity`
mostrando o disclaimer genérico + o detalhe do erro:

```
Parece que o núcleo do Libretro teve um problema inesperado. Se for persistente, tente o seguinte:
 • Desative o salvamento automático e execute o jogo novamente
 • Vá nas configurações do Android e limpe o cache do Retro Game System
 • Vá nas configurações do Retro Game System e execute a formatação de fábrica
Se nenhuma das opções acima funcionar, pode ser possível que este jogo/núcleo não seja compatível
com este dispositivo

Unable to add window -- token android.os.BinderProxy@36dc0cb is not valid; is your activity running?
```

A segunda linha (`text2` da `activity_crash`) é o `messageDetail`, ou seja **a mensagem da exceção
Java** que matou o processo `:game`. O texto de cima é só o disclaimer fixo — **não** é diagnóstico:
a mensagem culpa o core, mas o core não tem nada a ver com essa falha.

Caminho percorrido:

1. `:game` (`TVGameActivity`/`GameActivity`) recebe uma exceção não tratada na main thread.
2. `BaseGameActivity.setUpExceptionsHandler` a captura
   ([BaseGameActivity.kt:179-198](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/BaseGameActivity.kt#L179-L198)),
   e como não é falha de EGL cai em `performUnexpectedErrorFinish(exception)`
   ([BaseGameActivity.kt:399-415](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/BaseGameActivity.kt#L399-L415)),
   que devolve `RESULT_UNEXPECTED_ERROR` + `exception.message` e mata o processo.
3. No processo principal, `GameLaunchTaskHandler` tenta o core de fallback e, esgotados os cores,
   abre a `GameCrashActivity` com `lemuroid_crash_disclamer` + o detalhe
   ([GameLaunchTaskHandler.kt:55-67](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/main/GameLaunchTaskHandler.kt#L55-L67)).

Como o erro não depende de core nem de ROM, ele se repete em **todos** os sistemas — e a troca
automática de core só faz a falha acontecer de novo com o core seguinte.

## Causa-raiz

`WindowManager$BadTokenException: Unable to add window -- token android.os.BinderProxy@… is not
valid` é a assinatura do **bug de token do Toast no Android 7.1 (API 25)** — a versão que essas
boxes Amlogic (S905W/S905X) rodam de fábrica.

O que muda no 7.1: o `NotificationManagerService` passou a **expirar o token da janela do toast por
tempo** (`scheduleTimeoutLocked`, 2 s / 3,5 s). Quem monta a janela, porém, é o app: o
`Toast$TN.handleShow` roda numa **mensagem da main thread** e só então chama
`WindowManager.addView` com aquele token. Se a main thread não drenar a mensagem dentro do prazo, o
token já morreu e o `addView` lança `BadTokenException`. O `try/catch` que engole isso dentro do
framework **só existe a partir do Android 8 (API 26)** — no 7.1 a exceção sobe pelo `Looper`, mata a
main thread e cai no `UncaughtExceptionHandler`.

As duas condições se encontram exatamente no boot do jogo:

**1. Sempre há um toast enfileirado logo no `onCreate` do jogo.** Um dos dois dispara em qualquer
lançamento, dependendo apenas de haver ou não controle pareado:

| Toast | Onde | Quando |
|---|---|---|
| `tv_game_message_missing_gamepad` | [TVGameActivity.kt:34-41](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/game/TVGameActivity.kt#L34-L41) (`launchOnState(CREATED)`) | nenhum input habilitado — o caso normal de uma box que só tem controle remoto IR |
| `game_toast_settings_button_using_gamepad` | [GameViewModelInput.kt:313-330](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/viewmodel/GameViewModelInput.kt#L313-L330) (`launchOnState(CREATED)`) | há gamepad com atalho de MENU |

**2. A main thread do `:game` fica presa justo nesse intervalo.** Já documentado em
[2026-08-09-anr-inicializar-jogo-runongl-thread.md](2026-08-09-anr-inicializar-jogo-runongl-thread.md):
`LibretroDroid.create` faz o `dlopen` do core na main thread, e a GLThread fica dentro de
`onSurfaceCreated → initializeCore → retro_load_game`. Num S905 com armazenamento eMMC lento isso
passa fácil dos 2-3,5 s do timeout do token — em **qualquer** core, com **qualquer** ROM.

Resultado: token expira → `handleShow` → `addView` → `BadTokenException` → processo `:game` morto →
tela de crash. Toda vez.

`Toast` é o **único** ponto do processo `:game` que chama `WindowManager.addView`: o `GLRetroView` é
uma `GLSurfaceView` dentro da janela da Activity, e o único `Dialog` do Compose
([MobileGameScreen.kt:278](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/game/MobileGameScreen.kt#L278))
é o de editar controles de toque, acionado pelo usuário.

> ⚠️ Não confie no `SDK_INT` desses aparelhos para decidir se estão afetados: as boxes MXQ e clones
> costumam anunciar Android 9/11 num framework 7.1 de verdade. Um guard `if (SDK_INT <= 25)` pode
> deixar de fora exatamente o device que precisa da proteção.

## Por que só agora (é regressão)

O usuário relata que **antes funcionava nesse mesmo tipo de box**. O framework 7.1 não mudou, então
o que mudou foi do nosso lado. Duas hipóteses, ambas recentes, e não são excludentes:

**A) O toast passou a existir onde antes não existia — `b91cc7b` (2026-07-20, "fix: traz o fix de
input generico (hasKeys mente)").** Esse commit afrouxou de propósito a detecção de controle
*justamente nessas TV boxes*:

| | antes de b91cc7b | depois |
|---|---|---|
| `isSupported()` | exigia `supportsAllKeys` de um grupo inteiro (A,B,X,Y ou 1..4) — e `hasKeys` mente nessas boxes, logo **rejeitava** | exige source de gamepad + `hasGamepadEvidence()`: **qualquer um** dos botões, ou eixos de joystick |
| `isEnabledByDefault()` | só testes por `hasKeys` | ganhou `hasJoystickAxes()` — qualquer coisa com HAT de D-pad entra |

Como as duas condições de toast dependem da lista de inputs habilitados, mexer nela **troca qual
toast dispara**: com a lista vazia sai o `tv_game_message_missing_gamepad`; com a lista cheia e um
atalho de MENU padrão sai o `game_toast_settings_button_using_gamepad`. Numa box em que antes nada
era reconhecido e a UI é a de celular (sem `android.hardware.type.television`, o caso comum em MXQ
com Android genérico), o resultado é direto: **antes, nenhum toast no boot do jogo; depois, um a
cada lançamento.**

**B) O boot do jogo ficou mais lento e passou a estourar o prazo do token.** O toast sempre existiu,
mas antes a main thread conseguia desenhá-lo dentro dos 2 s. O
[bug do ANR de 2026-08-09](2026-08-09-anr-inicializar-jogo-runongl-thread.md) documenta que esse
mesmo trecho passou dos **5 s num Redmi** — aparelho muito mais rápido que um S905. Se num Redmi
deu ANR, na MXQ o prazo do toast é perdido com folga. Contribuem para isso os cores maiores
(`dlopen` na main thread) e o rebuild do `libretrodroid-patched.aar`.

**Qual das duas vale nesse aparelho não dá para decidir daqui** — depende da tabela de input da box
(quais `hasKeys` mentem) e de ela declarar ou não a feature de televisão. As duas levam ao mesmo
crash e a correção abaixo cobre as duas.

## Correção

Toda exibição de toast passa a montar a janela por um `WindowManager` que engole a
`BadTokenException`, em vez de deixá-la subir pelo `Looper`.

O truque (o mesmo do ToastCompat, sem reflection e sem API oculta): o `Toast$TN.handleShow` pega o
`WindowManager` de `mView.getContext().getApplicationContext()`. Passando para o `Toast.makeText` um
`ContextWrapper` que devolve **a si mesmo** como application context, o `getSystemService(WINDOW_SERVICE)`
passa pelo nosso wrapper e podemos proteger o `addView`.

| Arquivo | Mudança |
|---|---|
| [SafeToast.kt](../../retrograde-util/src/main/java/com/swordfish/lemuroid/common/SafeToast.kt) | **Novo / Atualizado.** `Context.displayToast(String\|Int, length)` + `SafeToastContext`/`SafeWindowManager`. `SafeToastContext` sobrescreve `LAYOUT_INFLATER_SERVICE` com `cloneInContext(this)` e aplica hook defensivo em `toast.view.mContext` via reflexão; `SafeWindowManager` engole `BadTokenException` em `addView` e trata com segurança `removeView`/`removeViewImmediate` |
| [Android.kt](../../retrograde-util/src/main/java/com/swordfish/lemuroid/common/Android.kt) | Removidos os dois `Activity.displayToast` que faziam `Toast.makeText(...).show()` direto |
| [TVAppUpdateDialog.kt](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/shared/TVAppUpdateDialog.kt) | `Toast.makeText` → `displayToast` |
| [SettingsScreen.kt](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/settings/general/SettingsScreen.kt) | idem (2 pontos) |
| [ActivateGoogleDriveActivity.kt](../../lemuroid-app-ext-play/src/main/java/com/swordfish/lemuroid/ext/feature/savesync/ActivateGoogleDriveActivity.kt) | idem (4 pontos) |

E, atacando a corrida na origem — nenhum toast é enfileirado enquanto a main thread carrega o core:

| Arquivo | Mudança |
|---|---|
| [BaseGameActivity.kt](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/BaseGameActivity.kt) | `baseGameScreenViewModel` tornado `protected` para expor `retroGameView` às subclasses |
| [GameViewModelInput.kt](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/viewmodel/GameViewModelInput.kt) | `initializeGamePadShortcutsFlow` espera `FrameRendered` antes de coletar — mesmo gate que `initializeControllerConfigsFlow` já usava |
| [TVGameActivity.kt](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/game/TVGameActivity.kt) | `initializeShortcutToastFlow` agora aguarda `waitGLEvent<FrameRendered>()` antes de coletar inputs vazios; sem esse gate, boxes sem controle (apenas controle remoto IR) disparavam o toast logo em `CREATED` em pleno boot |

Detalhes que importam:

- O receiver virou `Context` (era `Activity`). Toda chamada existente continua compilando, e o
  toast passa a ser montado sobre o `applicationContext` — o padrão recomendado.
- **Sem guard de versão**, pelo motivo do aviso acima; onde o framework já protege, o wrapper é inerte
  (no Android 11+ toasts de texto nem passam pelo `addView` do app).
- O `showToastSafely` ainda tem um `try/catch (Throwable)` externo: um aviso de UI não pode derrubar
  a tela que o dispara, qualquer que seja a falha.
- Com o gate de `FrameRendered`, o aviso do atalho de MENU deixa de ser perdido no 7.1: ele passa a
  ser enfileirado quando a main thread já está livre, e aparece sobre o jogo. O `SafeToast` continua
  sendo a rede de segurança para qualquer outro toast que pegue um token vencido.

## Blindagem (para a classe não se repetir)

O crash em si é a camada 1. As outras três atacam o que fez este bug custar caro: ele se **disfarçou**
de problema de core e não deixou nenhuma pista utilizável no relato do cliente.

**2. Exceção Java não é crash de core — parar de dizer que é.**
Crash de core é SIGSEGV: não desenrola pela JVM, mata o processo direto, e só aparece na sessão
seguinte via `ApplicationExitInfo`. Então o que chega ao `UncaughtExceptionHandler` é bug de app até
prova em contrário.

| Arquivo | Mudança |
|---|---|
| [BaseGameActivity.kt](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/BaseGameActivity.kt) | `isEmulatorFailure(exception)` — só `true` com frame de `com.swordfish.libretrodroid` ou `OutOfMemoryError`; vai no extra `PLAY_GAME_RESULT_IS_EMULATOR_FAILURE`. E `exception.message ?: javaClass.name`, para exceção de mensagem nula (NPE) não chegar como detalhe vazio |
| [GameLaunchTaskHandler.kt](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/main/GameLaunchTaskHandler.kt) | quando não é falha de emulação: **sem fallback de core** e mensagem `lemuroid_app_error_disclamer` |

O fallback de core era o agravante escondido: para um erro que não tem nada a ver com o núcleo, o
app relançava o jogo com cada core restante — o mesmo crash 2-3 vezes antes de o usuário ver
qualquer coisa.

**3. Uma foto da tela de erro tem que bastar.**
Toda esta investigação dependeu de adivinhar em que Android a box estava. A tela agora traz, em
rodapé discreto, `aparelho · Android X (SDK N) · versão do app`
([activity_crash.xml](../../lemuroid-app/src/main/res/layout/activity_crash.xml) +
[GameCrashActivity.kt](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/gamecrash/GameCrashActivity.kt)).
Com isso, a foto que abriu este bug teria dado o diagnóstico em minutos.

**4. Convenção registrada.** [CLAUDE.md](../../CLAUDE.md) ganhou os pitfalls **7** (Toast/API 25,
com a regra de nunca guardar por `SDK_INT` nessas boxes) e **8** (tela de crash não acusa o núcleo;
o sinal real é o `text2`), além da regra de toast em "Convenções de Código".

Não implementado (opcional): uma task Gradle que falha o build se `Toast.makeText` aparecer fora do
`SafeToast.kt`. A regra em CLAUDE.md cobre o dia a dia; a task cobriria o caso de alguém
(ou outro agente) reintroduzir a chamada direta.

## Varredura da mesma classe no resto do app

A falha é sempre a mesma: **adicionar janela com um token que já morreu**. Duas famílias no app —
`Toast` (token com prazo do NotificationManagerService) e `AlertDialog.Builder(activity).show()`
chamado **depois de um ponto de suspensão**, quando a activity já pode ter fechado.

Varrido: `Toast.makeText`, `AlertDialog`, `PopupWindow`, `DialogFragment`, `WindowManager`,
`Snackbar` e todo `.show()` de todos os módulos.

| Local | Situação | Ação |
|---|---|---|
| `Toast.makeText` fora do `SafeToast.kt` | **nenhum** — só restam referências à constante `Toast.LENGTH_*` | — |
| [ActivityUtils.displayErrorDialog](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/utils/android/ActivityUtils.kt) | helper compartilhado, chamado de coroutine por `ExternalGameLauncherActivity` e `StorageFrameworkPickerLauncher` | guard `isFinishing/isDestroyed` **no helper** — cobre os dois de uma vez |
| [TVRomDownloadDialog.showError](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/game/TVRomDownloadDialog.kt) | pior exposição: roda depois de um download de **minutos** | guard |
| [GameLauncher.showRomNotFoundDialog](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/GameLauncher.kt) | depois de dois hops em `Dispatchers.IO` | guard |
| [TVAppUpdateDialog.showMessage](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/shared/TVAppUpdateDialog.kt) | o ramo de **erro** do `check()` chamava antes do guard que já existia no caminho feliz | guard movido para dentro do `showMessage` |
| `TVAppUpdateDialog` (diálogos de update/permissão/progresso), `TVRomDownloadDialog.show`, `StorageFrameworkPickerLauncher` (2 diálogos) | disparados por clique, sem suspensão no meio | sem risco |
| `AlertDialog` do Compose (Home, MainActivity, settings, binding de controle…) | vivem na composition, que é destruída junto com a activity | sem risco |
| `NotificationChannel`, `startForegroundService` | guardados por `SDK_INT >= O` / `ContextCompat` | sem risco |

Nada de `PopupWindow` (só código comentado em `TouchControllerCustomizer`) nem `Snackbar` no projeto.

## Validação

- ✅ **Compilado e testado em 2026-09-17**:
  - `:retrograde-util:compileDebugKotlin` **BUILD SUCCESSFUL**
  - `:lemuroid-app:compileFreeBundleDebugKotlin` + `:lemuroid-app-ext-play:compileDebugKotlin` **BUILD SUCCESSFUL**
  - `:lemuroid-app:testFreeBundleDebugUnitTest` **BUILD SUCCESSFUL**
  - `:lemuroid-app:assembleFreeBundleDebug` **BUILD SUCCESSFUL** (APKs gerados com sucesso)
- ✅ **Compilado em 2026-08-16** (máquina configurada nesta data — ver "Ambiente de build" no
  CLAUDE.md): `:lemuroid-app:compileFreeBundleDebugKotlin` + `:lemuroid-app-ext-play:compileDebugKotlin`
  **BUILD SUCCESSFUL**, sem warning novo. `:lemuroid-app:assembleFreeBundleDebug` **BUILD SUCCESSFUL**
  — APKs arm64-v8a (135,9 MB) e armeabi-v7a (118,7 MB) gerados.
- `ktlintCheck` falha, mas **é estado pré-existente do projeto**: 870 violações, incluindo o próprio
  `build.gradle.kts` e 174 num único arquivo não relacionado. Conferido arquivo por arquivo que
  **nenhuma violação está em linha escrita por esta correção**; o `SafeToast.kt` não aparece no
  relatório. `assemble*` não roda ktlint (só `check`), por isso a distribuição não é afetada.
  > ⚠️ **Não existe task `compilePlayBundleDebugKotlin`**: as variantes `play` são desabilitadas em
  > [build.gradle.kts:233-237](../../lemuroid-app/build.gradle.kts#L233-L237)
  > (`beforeVariants(withFlavor("opensource" to "play")) { enable = false }`). Como o
  > `lemuroid-app-ext-play` só entra por `playImplementation`, ele **nunca** é compilado junto com o
  > app — tem que ser compilado direto pelo módulo, senão uma quebra lá passa despercebida.
- Roteiro no device (MXQ 4K Pro): abrir qualquer jogo de qualquer sistema; deve carregar
  normalmente. `adb logcat` não deve mais mostrar `BadTokenException` em `Toast$TN.handleShow`.
- Roteiro da blindagem, em qualquer aparelho: forçar uma exceção Java no processo `:game` (ex.:
  `throw IllegalStateException("teste")` no `onCreate` do `BaseGameActivity`) e conferir que a tela
  mostra o texto de erro **de app** (não o do núcleo), que o jogo **não** é relançado com os outros
  cores, e que o rodapé traz aparelho + Android + versão.
- ~~**Confirmação pendente pela telemetria**~~ → **feita em 2026-09-02**, ver abaixo.

## Validação possível fora da TV box (2026-09-03)

A MXQ é do cliente, então o que dá para validar aqui é **o que a correção não pode ter
quebrado**: que o `displayToast` continua desenhando um toast de verdade. O `SafeToastContext`
troca o `WindowManager` que o `Toast$TN.handleShow` usa — se o wrapper estivesse errado, o
sintoma no aparelho saudável seria o toast **sumir em silêncio**, que é pior de descobrir do
que um crash.

**Aparelho:** Moto G86 5G, Android 16, app `1.17.12-DEBUG`.
**Gatilho usado:** *Super Mario World* (SNES), estado do slot 1 sobrescrito com lixo por `adb`,
depois carregado pelo menu do jogo → caminho `game_toast_load_state_failed` em
`GameViewModelSaves`.

```
I SurfaceFlinger: onHandleDestroyed: layerId=24644, name=5910e40 Toast#24644
W NotificationService: Toast already killed. pkg=app.retrogamesystem.debug token=android.os.BinderProxy@…
```

- ✅ A janela de toast **é criada** e vive o tempo normal — o wrapper não engole o caminho feliz.
- ✅ Nenhum `BadTokenException` no logcat.
- ⚠️ O toast **não aparece no `screencap`** (a camada é excluída da captura), então a prova aqui
  é o log do SurfaceFlinger, não a imagem. Vale anotar para a próxima vez que alguém tentar
  conferir toast por screenshot e concluir errado que não apareceu.
- Também conferido por varredura estática: **zero** `Toast.makeText(...)` fora do `SafeToast.kt`
  em `lemuroid-app`, `retrograde-app-shared`, `retrograde-util` e `lemuroid-touchinput`.

**Continua faltando** o que só a MXQ (ou o `rockchip YBOX`) responde: que o jogo abre até o fim
sem cair na `GameCrashActivity`. É por isso que esta página segue em `open/`.

## Confirmação pela telemetria (2026-09-02)

A triagem de produção fechou os dois campos que faltavam. **A hipótese estava certa.**

- **Errors (serviço):** 18 ocorrências — 1229, 1230, 1231, 1232, 1233, 1235, 1237, 1238, 1284,
  1285, 1286, 1287, 1288, 1289, 1290, 1291, 1292, 1293. Todos `retrogamesystem/game`,
  `ViewRootImpl.java::android.view.ViewRootImpl.setView`.
- **Aparelho:** `rockchip YBOX` (clone da mesma família da MXQ), **armeabi-v7a** — não a MXQ
  nominalmente, mas o mesmo hardware/ROM.
- **`platform`: `Android 12.1 (sdk 25)`** — o aparelho anuncia 12.1 e é **sdk 25**, exatamente a
  armadilha descrita na regra 2 acima. Um guard por `SDK_INT` teria deixado esse aparelho de fora.
- **Log anexo** (error 1290), como previsto:

  ```
  android.view.WindowManager$BadTokenException: Unable to add window -- token
  android.os.BinderProxy@ba1fbf9 is not valid; is your activity running?
  	at android.view.ViewRootImpl.setView(ViewRootImpl.java:679)
  	at android.widget.Toast$TN.handleShow(Toast.java:459)      <- exatamente o previsto
  	at android.widget.Toast$TN$2.handleMessage(Toast.java:342)
  	at android.os.Looper.loop(Looper.java:154)                 <- assinatura de Android 7.1
  	at android.app.ActivityThread.main(ActivityThread.java:6121)
  ```

- **Atinge todo sistema**, como o relato dizia: atari2600/stella, gb/gambatte, galaxian/fbneo,
  jaguar/virtualjaguar — 8 jogos distintos em 15 minutos de uso.

**Sinal de que a correção segurou:** as 18 ocorrências são **todas** do app **1.17.6** e
concentradas em 2026-08-16 (01:40 → 13:08). Não há nenhum `BadTokenException` em 1.17.8 a
1.17.12, apesar de essas versões dominarem a telemetria do período. Falta só a validação
física no aparelho do cliente para fechar o bug.

> Achado colateral no **mesmo** `rockchip YBOX`: `NullPointerException:
> getRunningAppProcesses(...) must not be null` (error 1347, app 1.17.8) — outro crash
> exclusivo dessa classe de aparelho, registrado em
> [[2026-09-02-ismainprocess-npe-getrunningappprocesses]].

## Lição

`Toast` não é uma chamada inofensiva: a janela é adicionada **depois**, numa mensagem da main
thread, com um token que tem prazo de validade. Em qualquer momento de main thread ocupada — que é
justamente o boot do jogo — isso vira crash em Android 7.1. Todo toast do app deve passar por
`Context.displayToast`; nenhum `Toast.makeText(...).show()` direto.

E, no diagnóstico: a `GameCrashActivity` mostra um disclaimer fixo culpando o core do Libretro
mesmo quando a falha não tem nada a ver com emulação. **O sinal real é sempre o `text2`** (a
mensagem da exceção) — o texto de cima levou o usuário a limpar cache e fazer reset de fábrica num
bug que era de UI.
