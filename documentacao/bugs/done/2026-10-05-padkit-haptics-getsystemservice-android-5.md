# [BUG] Todo jogo da UI mobile morre no Android 5.0–5.1 — haptics do padkit chama `Context.getSystemService(Class)` (API 23)

**Data:** 2026-10-05
**Status:** Corrigido (2026-10-05) — instrumentação de bytecode do padkit no build + saída do `:game` que não
volta ao handler
**Severidade:** Alta para API 21–22 (`minSdk`) — nenhum jogo abre na UI mobile, com ou sem gamepad
**Branch:** version9
**Origem:** validação no `lemu_api21_1gb` de
`documentacao/bugs/done/2026-10-05-padkit-kotlinx-datetime-java-time-android-7.md`

## Sintoma

AVD `lemu_api21_1gb` (Android 5.0, x86_64), debug 1.17.24 (já com o desugaring do bug acima), *Lumines*
(PSP) por deep link: o `:game` cai na primeira composição da tela de jogo, antes do `[BOOT]` do core,
e vai para a tela de erro de app:

```
E/BaseGameActivity: java.lang.NoSuchMethodError: No virtual method getSystemService(Ljava/lang/Class;)Ljava/lang/Object;
    in class Landroid/content/Context; or its super classes
    at gg.padkit.haptics.AndroidHapticGenerator.buildVibrator(HapticGenerator.android.kt:59)
    at gg.padkit.haptics.AndroidHapticGenerator.<init>(HapticGenerator.android.kt:36)
    at gg.padkit.haptics.HapticGenerator_androidKt.rememberHapticGenerator(HapticGenerator.android.kt:92)
    at gg.padkit.PadKitKt.PadKit(PadKit.kt:86)
    at com.swordfish.lemuroid.app.mobile.feature.game.MobileGameScreenKt$MobileGameScreen$1.invoke(MobileGameScreen.kt:114)
    at androidx.compose.foundation.layout.BoxWithConstraintsKt$BoxWithConstraints…
W/GameLaunchTaskHandler: Non-emulator failure in game process: No virtual method getSystemService(…)
```

Reproduzido três vezes. **Efeito colateral:** depois do crash o processo `:game` não morre — laço de
`RuntimeException: System.exit returned normally, while it was supposed to halt JVM.` em
`BaseGameActivity$finishAndExitProcess$1.invokeSuspend(BaseGameActivity.kt:594)`, que enche o buffer do
logcat em segundos e resiste a `am force-stop` (só `run-as … kill -9` resolveu).

## Causa (confirmada)

- `padkit-android:1.0.0-beta1`, `AndroidHapticGenerator.buildVibrator`: `SDK_INT >= 31` →
  `getSystemService(VibratorManager::class.java)`; senão `getSystemService(Vibrator::class.java)` — a
  sobrecarga `getSystemService(Class)` é **API 23** e não tem guarda. Os `VibrationEffect` (API 26) são
  guardados (`SDK_INT >= 26`, senão `null`).
- `rememberHapticGenerator()` constrói o `AndroidHapticGenerator` **sempre** (não recebe o
  `HapticFeedbackType`), e o `PadKit` o chama em linha reta (`PadKitKt`, bytecode 866). Desligar a
  vibração nos ajustes não evita o crash.
- `PadKit` é a raiz da `MobileGameScreen` — a `GLRetroView` fica dentro dele. Logo: todo jogo, todo
  sistema, com ou sem gamepad, em API 21–22. API 23+ não é afetada.
- No dex do APK, `gg.padkit.haptics.AndroidHapticGenerator` é o único chamador de
  `Context.getSystemService(Class)` no caminho do `:game` (os demais são `androidx.core` `*Api23Impl`
  e `ShortcutManagerCompat`/`ShortcutsGenerator`, do processo principal).
- **Introduzido pelo `25a4b58` (2026-04-21, ~1.17.0)**, que baixou o `minSdkVersion` de 23 (valor do
  upstream e do commit raiz) para 21 sem auditar as dependências: com minSdk 23 esse código nunca rodava
  abaixo da API 23. Desde então nenhum jogo abre no Android 5.0–5.1 pela UI mobile.
- **Não é coberto pelo desugaring** (é API de framework) e **subir o padkit não resolve**: a 1.0.0 tem o
  mesmo `buildVibrator`.

## Análise da correção (2026-10-05, antes de editar código)

### Símbolos abertos

- `padkit-android-1.0.0-beta1` (`lib-release.aar` do cache do Gradle), `javap -c`:
  - `AndroidHapticGenerator.buildVibrator` — os dois ramos usam
    `invokevirtual android/content/Context.getSystemService:(Ljava/lang/Class;)Ljava/lang/Object;`
    (offsets 11 e 32); só o ramo `>= 31` tem guarda.
  - `HapticGenerator_androidKt.rememberHapticGenerator` — passa `LocalContext.current.applicationContext`.
- `androidx.core.content.ContextCompat.getSystemService(Context, Class)` **no dex do APK debug**
  (`dexdump`, `classes20.dex`): `SDK_INT >= 23` → `Api23Impl` (a mesma chamada de hoje); abaixo,
  `getSystemServiceName` → `LegacyServiceMapHolder.SERVICES` → `context.getSystemService(String)`. O
  `<clinit>` do holder põe `Vibrator.class → "vibrator"` fora do bloco `SDK_INT >= 22`. A pilha é a mesma da
  chamada original (`[Context, Class] → Object`): trocar uma instrução pela outra não mexe em frames.
- AGP efetivo 8.7.1, `gradle-api-8.7.1.jar`: `AsmClassVisitorFactory<P>` (`createClassVisitor`,
  `isInstrumentable(ClassData)`, `instrumentationContext.apiVersion`),
  `Instrumentation.transformClassesWith(Class, InstrumentationScope, (P) -> Unit)`,
  `InstrumentationScope.ALL` = projeto + dependências.
- `BaseGameActivity.setUpExceptionsHandler` — handler default do `:game`: reporta →
  `performUnexpectedErrorFinish` → `finishAndExitProcess`. `finishAndExitProcess` — corrotina em
  `Dispatchers.IO`: `delay(animação)` → `TelemetryReporter.awaitPending` → `exitProcess(0)`.
  `restartInFreshProcess` já usa `Process.killProcess`.
- `TelemetryReporter.reportThrowable`/`report` — nunca lança; deduplica por `componente|mensagem`; envia
  por thread nova, que não sobe com a VM em shutdown: o laço não inunda o servidor.

### O que provou o quê

- `audit_dex_api_level.py <debug x86_64> api-versions.xml --android`: em `Lgg/padkit/`, só
  `AndroidHapticGenerator` (API 23 `getSystemService(Class)`; 26/29/31 guardados por `SDK_INT`) e
  `AndroidHapticGenerator$generate$1` (API 26 `vibrate(VibrationEffect)`, só lançado com `SDK_INT >= 26`).
  Nada mais do padkit acima da 21.
- Reprodução no `lemu_api21_1gb`, debug 1.17.24, deep link do *Lumines* (id 41927), logcat do `:game`:
  1. `17:03:17.824 D AndroidRuntime: Shutting down VM` — a main thread morreu na `NoSuchMethodError`;
  2. `17:03:18.486` o handler roda na main: reporta, `finish()`, lança a corrotina de saída;
  3. `17:03:18.902` thread IO: `java.lang.InternalError: Thread starting during runtime shutdown` em
     `Thread.start` ← `Runtime.exit(Runtime.java:272)` ← `System.exit` ← `finishAndExitProcess$1`;
  4. dali em diante, `RuntimeException: System.exit returned normally…` a cada ~400 ms (o `delay` da
     animação): 55 voltas em ~20 s.

  Mecânica: o `Runtime.exit` do libcore liga `shuttingDown` **antes** de dar `start()` nos shutdown hooks.
  Com a VM já em shutdown (a main saiu pela exceção e o `AndroidRuntime` seguiu para o `DestroyJavaVM`), o
  `start()` lança `InternalError`; toda chamada seguinte vê `shuttingDown` e retorna. O `exitProcess` do
  Kotlin converte o retorno em `RuntimeException`, que cai no mesmo handler, que chama
  `finishAndExitProcess` de novo.

### Hipóteses descartadas

- **Contornar no app com um `Context` que tenha `getSystemService(Class)`** — impossível: o `invoke-virtual`
  resolve o método em `android.content.Context` na ligação, qualquer que seja o objeto recebido.
- **Fork do padkit (fonte) empacotado como AAR local** (a correção candidata da 1ª análise) — o padkit é KMP
  (build próprio), viraria binário versionado, e a dependência entra como `api` num módulo de biblioteca
  (`lemuroid-touchinput`), onde AAR local perde os transitivos e o AGP recusa empacotar. A troca é de
  **uma instrução** com a mesma pilha: cabe na instrumentação de bytecode do AGP, versionada como código, e
  continua valendo quando o padkit subir (a 1.0.0 tem o mesmo `buildVibrator`).
- **Fábrica ASM no `buildSrc`** — o classloader do `buildSrc` é pai do que carrega o AGP: com `compileOnly`,
  `NoClassDefFoundError`; com `implementation` de `gradle-api`/ASM, as classes do `buildSrc` sombreiam as do
  AGP (pai primeiro) e cada bump do AGP vira descompasso silencioso. Vai no `lemuroid-app/build.gradle.kts`,
  que compila contra o AGP efetivo.
- **Não compor o `PadKit` abaixo da API 23** — tira o pad touch inteiro: sem gamepad, nada é jogável.
- **"O `Runtime.exit` retorna porque já há um shutdown em andamento"** (hipótese da 1ª análise) — meio
  certo: quem deixa `shuttingDown` ligado é a **primeira** chamada, que morre com `InternalError` ao iniciar
  os hooks; as seguintes é que retornam.

### Tarefas

1. `lemuroid-app/build.gradle.kts`, no **fim** do arquivo (o baseline do ktlint é por linha):
   `AsmClassVisitorFactory` que, só em classes `gg.padkit.*`, troca
   `invokevirtual Context.getSystemService(Class)` por `invokestatic ContextCompat.getSystemService(Context, Class)`,
   registrada em `androidComponents.onVariants` com `InstrumentationScope.ALL`. **Prova:** `dexdump` do APK
   mostra `buildVibrator` chamando `ContextCompat.getSystemService`; o `audit_dex_api_level.py --android` não
   lista mais API 23 em `Lgg/padkit/`; no `lemu_api21_1gb` o *Lumines* passa do `PadKit` e chega ao boot
   do core, com o pad na tela.
2. `BaseGameActivity`: (a) a saída do `finishAndExitProcess` cai em `Process.killProcess` se o
   `exitProcess` lançar; (b) com a saída já pedida, o handler mata o processo em vez de recomeçar o fluxo de
   erro — cobre as outras falhas do shutdown (ex.: o `DefaultExecutor` das corrotinas, que recria a thread
   depois de 1 s ocioso). **Prova:** build só com esta tarefa, mesmo deep link: o `:game` some do `ps` logo
   depois da tela de erro, sem nenhum `System.exit returned normally` no logcat.
3. `CLAUDE.md` (pitfall 16, regra 2: o haptics deixa de ser pendência; regra nova sobre a saída do
   `:game`) e este doc para `done`.

Fora do escopo: contar usuários em API 21–22 na telemetria (a 1ª análise queria isso para definir a
prioridade — com a correção pronta, não decide mais nada) e mandar o patch para o upstream do padkit (ação
externa, fica a critério do dono).

Para testar no `lemu_api21_1gb`: o AVD não tem armazenamento externo (sem `sdcard.img`) e a pasta de ROMs
vira `/roms`; a ROM do *Lumines* já está em `files/roms/psp/` do app debug, com a `fileUri` da linha 41927
apontada para lá (ou lançar o AVD com `-sdcard`, ver "SD card / segundo volume" no `CLAUDE.md`).

## Correção

1. **Haptics do padkit** — `lemuroid-app/build.gradle.kts`, fim do arquivo: `PadkitGetSystemServiceCompat`
   (`AsmClassVisitorFactory`), registrada em `androidComponents.onVariants` com `InstrumentationScope.ALL`.
   Nas classes `gg.padkit.*`, troca `invokevirtual Context.getSystemService(Class)` por
   `invokestatic ContextCompat.getSystemService(Context, Class)`. Roda antes do dex/R8 em todo variant e
   continua valendo se o padkit subir. O `ClassData.className` chega com pontos (conferido no
   `AsmInstrumentationManager.doInstrumentClass` do AGP 8.7.1), daí o `startsWith("gg.padkit.")`.
2. **Laço do `exitProcess`** — `BaseGameActivity`:
   - `exitGameProcess()`: `exitProcess(0)` com fallback `Process.killProcess(Process.myPid())` se ele lançar
     (loga `W exitProcess failed; killing the game process`);
   - `exitRequested` (companion, `@Volatile`), ligado no fim do `finishAndExitProcess`, depois do
     `finish()`: com ele ligado, o handler default do `:game` reporta e mata o processo em vez de refazer o
     fluxo de erro.
3. `lemuroid-app/config/ktlint/baseline.xml`: 21 entradas do `BaseGameActivity.kt` deslocadas (+6 depois
   do handler, +23 depois do `finishAndExitProcess`), cada uma conferida pelo conteúdo da linha.

## Validação

- **Build #1, só a correção 2** (debug x86_64, `lemu_api21_1gb`, *Lumines* id 41927) — o crash do haptics
  continua, como esperado, mas a saída fecha:
  `17:15:35.622 Shutting down VM` → `17:15:36.311` handler (`NoSuchMethodError`) →
  `17:15:36.718 W BaseGameActivity: exitProcess failed; killing the game process`
  (`InternalError: Thread starting during runtime shutdown`) →
  `17:15:36.723 ActivityManager: Process app.retrogamesystem.debug:game (pid 3693) has died` →
  `17:15:36.902 Displayed …GameCrashActivity`. Nenhum `System.exit returned normally` (antes: 55 em ~20 s).
- **Build #2, correções 1 + 2:**
  - `dexdump` do APK: `AndroidHapticGenerator.buildVibrator` chama
    `ContextCompat.getSystemService(Context, Class)` nos dois ramos. `audit_dex_api_level.py --android`:
    `AndroidHapticGenerator` sem a API 23 (sobram 26/29/31, guardadas); `$generate$1` com a 26 (guardada).
  - `lemu_api21_1gb` (Android 5.0): o *Lumines* abre — `[BOOT] PPSSPP …`, `[LOADER] ULUS10002 : LUMINES`,
    60 fps, pad do PSP na tela. Toques nos botões e arrastos do analógico: nenhuma exceção no `:game`.
    `BACK` → autosave → `System.exit called, status: 0` → `:game` morto, sem passar pelo fallback.
  - SM-A127M (Android 13, arm64): *Super Mario Bros. 3* (id 38147) abre; 3 toques no pad = 3 vibrações do
    app no `dumpsys vibrator_manager` (`Prebaked{effect=CLICK}`, o efeito do padkit na API ≥ 29). `BACK` →
    `System.exit called, status: 0` → `:game` morto.
- **Gates:** `ktlintKotlinScriptCheck` (raiz e `:lemuroid-app`) passa. `:lemuroid-app:ktlintMainSourceSetCheck`
  só acusa o passivo de `StreamingRomsManager.kt` (`2026-10-05-lint-ktlint-gates-falhando-passivo-novo.md`),
  nada no `BaseGameActivity.kt`. `:lemuroid-app:lintFreeBundleDebug`: `5 errors, 1 warnings (32 errors, 59
  warnings filtered by baseline)` — exatamente o passivo daquele bug (os 5 `NewApi` do `CrashTelemetry.kt` e
  o `ChromeOsAbiSupport` do `splits`); nada nos arquivos desta correção.
- **Não validado: APK de release.** Gerá-lo sobrescreveria os APKs da 1.17.25 que outra sessão tinha acabado
  de montar. A instrumentação roda antes do R8 em todo variant; a auditoria no release com `--mapping` (regra
  2 do pitfall 16) fica para o próximo build de release.
- O APK debug do build #2 saiu ~12 MB maior: buracos do empacotamento incremental (`arquivo − Σ entradas
  comprimidas` = 12,04 MB nos três APKs), porque o dex de todas as dependências foi refeito. O conteúdo não
  mudou (dex: 84.071.896 → 84.072.396 bytes). Release não é empacotado de forma incremental.

## Lição

- **API de framework acima do `minSdk` numa dependência não pede fork.** Quando o conserto é trocar uma
  chamada pela versão `*Compat` de mesma pilha, a instrumentação de bytecode do AGP (`AsmClassVisitorFactory`
  + `InstrumentationScope.ALL`) faz isso no build, versionada como código e válida para qualquer versão da
  dependência. Fork/AAR local fica como último recurso.
- **Handler que encerra o processo não pode ser re-entrante.** Se a saída falha, a falha volta ao mesmo
  handler. E a main que morre numa exceção leva a VM ao shutdown: dali em diante nenhuma thread nova sobe, e
  o `System.exit` do Android 5 para de funcionar. `Process.killProcess` sempre funciona.
- Em laço, ler a **primeira** volta. A 1ª análise olhou as voltas repetidas (`System.exit returned normally`)
  e chegou meio perto; quem mostrou a causa foi a primeira (`InternalError` em `Thread.start`).
