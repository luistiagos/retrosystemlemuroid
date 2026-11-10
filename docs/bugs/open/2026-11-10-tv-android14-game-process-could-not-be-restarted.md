# [BUG] Jogo fecha na inicialização na Android TV (Android 14) com `Game process could not be restarted (2 attempts)`

**Data:** 2026-11-10
**Status:** Em validação (2026-11-10)
**Severidade:** Alta — impede a abertura do jogo na Android TV afetada
**Complexidade:** Alta (ciclo de vida do processo `:game`, `BaseGameActivity`, `GameProcessSession`)
**Branch:** version10
**Origem:** Foto da tela de erro (`GameCrashActivity`) enviada pelo usuário (`Expressluck Multilaser Android TV · Android 14 (SDK 34) · 1.17.28`)

## Sintoma

Ao abrir um jogo em uma **Expressluck Multilaser Android TV** rodando **Android 14 (SDK 34)** na versão **1.17.28**, o aplicativo exibe a tela `GameCrashActivity` com o aviso de erro interno de aplicativo (`R.string.lemuroid_app_error_disclamer`) e o detalhe técnico:

```
Game process could not be restarted (2 attempts)
Expressluck Multilaser Android TV · Android 14 (SDK 34) · 1.17.28
```

## Evidência

- A string `"Game process could not be restarted ($restarts attempts)"` é emitida exclusivamente em `GameLaunchTaskHandler.relaunchInFreshProcess` quando `BaseGameActivity.onCreate` recebe `false` de `GameProcessSession.tryClaim()` e devolve `RESULT_RESTART_IN_FRESH_PROCESS` por `MAX_PROCESS_RESTARTS` tentativas seguidas (`restarts = 0`, `1` e `2`).
- Como `BaseGameActivity.restartInFreshProcess()` executa `Process.killProcess(Process.myPid())` (`SIGKILL` síncrono na main thread do `:game`) antes de retornar do `onCreate`, cada tentativa (`restarts = 0, 1, 2`) matou o processo `:game` em que rodou e a tentativa seguinte subiu um processo `:game` novo (onde `GameProcessSession.claimed` nasce `false`).

## Causa-raiz

Foram identificadas três falhas combinadas no ciclo de vida de inicialização do processo `:game`:

1. **Recriação da `BaseGameActivity` na inicialização no Android 14 (SDK 34) antes da criação do core (`GLRetroView`):**
   - No `AndroidManifest.xml`, `TVGameActivity`, `GameActivity` e `ExternalGameLauncherActivity` declaravam todas as 17 flags de `android:configChanges` existentes até a API 33, mas omitiam `grammaticalGender` (introduzida na **API 34 / Android 14**).
   - Além disso, quando o Android recriava a `BaseGameActivity` durante a inicialização da janela (`onDestroy` com `isChangingConfigurations == true` -> segundo `onCreate(savedInstanceState != null)` no **mesmo** processo `:game` recém-criado), `GameProcessSession.tryClaim()` já havia marcado `claimed = true` no primeiro `onCreate` — **mesmo antes de `GameViewModelRetroGameView.createRetroView` instanciar qualquer `GLRetroView` ou carregar qualquer `.so` nativo**.
   - A segunda chamada a `onCreate` no mesmo processo recebia `tryClaim() == false`, matava o processo recém-nascido e pedia relançamento (`restarts + 1`), repetindo o ciclo determinístico em todos os processos novos até estourar `MAX_PROCESS_RESTARTS`.
   - Adicionalmente, se a recriação cancelava o `lifecycleScope` da primeira instância enquanto `GameViewModelRetroGameView.initialize` estava em `GameState.Loading`, o estado no `ViewModel` permanecia preso em `Loading` e a segunda instância abortava em `if (currentState != GameState.Uninitialized) return`.

2. **Duplo disparo de lançamento na TV (`TVGameActivity` sem `singleTop` e `GameLauncher` sem guarda de concorrência):**
   - Diferente da `GameActivity` mobile (`launchMode="singleInstance"`), a `TVGameActivity` não declarava `launchMode="singleTop"`, e `GameLauncher.launchGameAsync` não impedia chamadas concorrentes durante o preparo em `Dispatchers.IO` (comum em controles remotos IR/Bluetooth de Android TV que enviam duplo clique). Duas intents seguidas criavam duas instâncias de `TVGameActivity` no mesmo processo `:game`, fazendo a segunda falhar em `tryClaim()` e matar o processo da primeira.

3. **Detecção de término do processo `:game` dependente apenas de `ActivityManager.getRunningAppProcesses()`:**
   - `GameProcessSession.awaitGameProcessExit` dependia exclusivamente de `getRunningAppProcesses()`, que em diversas ROMs de Android TV / TV box retorna `null` ou omite processos secundários (pitfall 3 do `CLAUDE.md`), fazendo a espera retornar em 0 ms enquanto o processo `:game` anterior ainda estava saindo.

## Correção

1. **`AndroidManifest.xml`**:
   - Adicionado `grammaticalGender` em `android:configChanges` de `TVGameActivity`, `GameActivity` e `ExternalGameLauncherActivity`.
   - Definido `android:launchMode="singleTop"` em `TVGameActivity` (e `Intent.FLAG_ACTIVITY_SINGLE_TOP` em `BaseGameActivity.launchGame`).
2. **`GameProcessSession.kt` / `BaseGameActivity.kt` / `GameViewModelRetroGameView.kt`**:
   - `GameProcessSession` passa a distinguir se o core nativo (`GLRetroView`) já foi criado (`onCoreCreated()`) ou se o processo está saindo (`onProcessExiting()`). Se a `BaseGameActivity` for recriada pelo sistema (`savedInstanceState != null` / `onActivityDestroyed(isChangingConfigurations = true)`) **antes** de `createRetroView()` ser chamado, a sessão é preservada no mesmo processo sem violar a regra de uma única `GLRetroView` por processo.
   - Em `GameViewModelRetroGameView.initialize`, caso a corrotina seja cancelada durante `GameState.Loading` (por recriação da Activity), o estado volta para `GameState.Uninitialized` para não travar a instância recriada.
   - Rastreamento do processo `:game` via lock file do kernel (`game_process.lock` adquirido em `GameProcessInitializer` e mantido até o `do_exit()` do kernel, sem liberar antes em Java) combinado com `Os.kill(pid, 0)` (syscall direta de mesmo UID disponível desde a API 21) e `PLAY_GAME_RESULT_PID` em `awaitGameProcessExit`, além de grace period de 150 ms para limpeza no `ActivityManagerService`.
3. **`GameLauncher.kt`**:
   - Adicionada guarda atômica (`isLaunching`) em `launchGameAsync` e chamada a `GameProcessSession.awaitGameProcessExit` antes de `BaseGameActivity.launchGame`.

## Validação

- **Testes unitários JVM:**
  - `GameProcessSessionTest.kt`: 6 testes cobrindo sessão única por processo, recriação de Activity permitida antes de `onCoreCreated()`, recriação rejeitada após `onCoreCreated()`, bloqueio de novas sessões após `onProcessExiting()` e ciclo de vida do `FileLock`.
  - Execução: `.\gradlew.bat testFreeBundleDebugUnitTest` -> `BUILD SUCCESSFUL` (10 suites, 60 testes, 0 falhas).

- **Qualidade de código e análise estática:**
  - `.\gradlew.bat :lemuroid-app:ktlintMainSourceSetCheck :lemuroid-app:ktlintTestSourceSetCheck :lemuroid-app:ktlintKotlinScriptCheck` -> `BUILD SUCCESSFUL` (0 violações).
  - `.\gradlew.bat :lemuroid-app:lintFreeBundleDebug` -> `BUILD SUCCESSFUL` (0 erros novos).

- **Teste End-to-End (E2E) no Emulador Android TV (`lemu_tv_api25`, Android TV API 25 x86):**
  - Build do APK com ABI de emulador: `.\gradlew.bat :lemuroid-app:assembleFreeBundleDebug -PdevAbi=x86,x86_64`.
  - Instalação: `adb install -r -t lemuroid-app/build/outputs/apk/freeBundle/debug/lemuroid-app-free-bundle-x86-debug.apk` no `emulator-5554`.
  - **Cenário 1 (Primeira inicialização de jogo TV):** Lançamento de *Super Mario World* (SNES, id `49001`) via deep link leanback. O processo `:game` (PID 3419) subiu de primeira, adquiriu o lock, instanciou o core `snes9x` e renderizou frames continuamente (`libretrodroid: VIDEOFRAMES 720`, `EMUFPS ~20 fps`). Screenshot capturada confirmou o jogo rodando na tela.
  - **Cenário 2 (Saída e relançamento imediato):** Envio de `KEYCODE_BACK`. O processo `:game` foi destruído imediatamente e a tabela de descritores foi liberada pelo kernel. Relançamento imediato disparou um processo `:game` novo (PID 3545), que adquiriu o lock e iniciou a emulação sem qualquer erro ou reinicialização (`VIDEOFRAMES 540`).
  - **Cenário 3 (Simulação de duplo clique rápido / D-Pad bounce):** Disparo consecutivo de dois lançamentos simultâneos. A guarda atômica `isLaunching` em `GameLauncher` e `launchMode="singleTop"` em `TVGameActivity` absorveram o segundo evento sem criar instâncias duplicadas no mesmo processo. O logcat registrou 0 chamadas de `GameCrashActivity` e 0 mensagens de `could not be restarted`.
  - **Cenário 4 (Mudança de configuração):** Alteração de escala de fonte do sistema via `settings put system font_scale 1.15`. O processo `:game` manteve-se estável e ativo sem falhas.

## Lição

- **Recriação de Activity durante o boot não é segunda sessão de core.** O motivo pelo qual o `:game` não pode hospedar duas sessões é o estado estático do `.so` nativo após `GLRetroView` (`dlopen`/`retro_init`/`retro_deinit`). Bloquear recriação da Activity antes mesmo de `createRetroView` transforma qualquer mudança de configuração de janela no boot (como `grammaticalGender` na API 34) num loop infinito de reinicialização de processo.
- **Nunca liberar em Java um `FileLock` usado como sentinela de vida de processo.** O `fcntl` lock deve permanecer aberto até que o próprio kernel Linux feche a tabela de descritores do processo em `do_exit()`, combinado com `Os.kill(pid, 0)` para verificar a existência do processo na tabela de tasks do kernel independentemente do `ActivityManager`.

