# Correções recursivas — 2026-03-20

## Resumo
- Nova varredura recursiva concluída.
- Correções aplicadas em nullability, escopo de coroutine e fluxo de bindings/input.
- Compilação validada com sucesso após as alterações.

## Correções aplicadas

### Nullability e força de unwrap
- `SafeStringsSetPreferenceSettingValueState.kt`: removido `!!` no valor inicial.
- `CoreOption.kt`: validação explícita para `description`, `key` e `value` nulos.
- `ExternalGameLauncherActivity.kt`: parsing seguro de `gameId` via deep link.
- `ShortcutsGenerator.kt`: `coverFrontUrl` tratado como opcional.
- `StorageAccessFrameworkProvider.kt`: remoção de vários `!!` em `DocumentFile` e streams.
- `DocumentFileParser.kt`: validação de `openInputStream()` antes de usar `ZipInputStream`.
- `StorageProviderRegistry.kt`: erro explícito para esquema de URI não suportado.
- `StorageFilesMerger.kt`: remoção de `!!` ao recombinar arquivos M3U.
- `GameLaunchTaskHandler.kt`: proteção contra `null` ao finalizar jogo.
- `BaseGameActivity.kt`: remoção de `tiltConfig!!`.

### Coroutines e escopo
- `GameInteractor.kt`: trocado `GlobalScope` por `lifecycleScope`.
- `GameLauncher.kt`: trocado `GlobalScope` por `lifecycleScope`.
- `MainActivity.kt`: trocado `GlobalScope.safeLaunch` por `lifecycleScope.launch`.
- `MainTVActivity.kt`: trocado `GlobalScope.safeLaunch` por `lifecycleScope.launch`.
- `ExternalGameLauncherActivity.kt`: trocado `GlobalScope.safeLaunch` por `lifecycleScope.launch`.
- `InputBindingUpdater.kt`: removido `runBlocking`, usando `lifecycleScope` da activity.
- `ShortcutBindingUpdater.kt`: removido `runBlocking`, usando `lifecycleScope` da activity.
- `TiltSensor.kt`: removido `GlobalScope.launch` e uso de `restOrientation!!`.

### Outras correções de robustez
- `TiltSensor.kt`: `minByOrNull()` com fallback seguro.
- `ShortcutsGenerator.kt`: `getSystemService()` tratado como nullable.
- `StorageAccessFrameworkProvider.kt`: tratamento seguro de `DocumentFile.fromSingleUri()`.

## Validação
- `:lemuroid-app:compileFreeDynamicDebugKotlin` — sucesso
- `:retrograde-app-shared:compileDebugKotlin` — sucesso
- `:lemuroid-touchinput:compileDebugKotlin` — sucesso

## Observação
- `BaseGameActivity.kt` ainda mantém um `GlobalScope.launch` em `finishAndExitProcess()` de forma intencional para encerrar o processo após a animação final.
