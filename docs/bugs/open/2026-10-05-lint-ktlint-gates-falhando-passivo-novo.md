# [BUG] `lintFreeBundleDebug` e `ktlintCheck` voltaram a falhar — passivo novo de 30/09 e 04/10

**Data:** 2026-10-05
**Status:** Aberto (registrado de passagem; não corrigido)
**Severidade:** Baixa no app, alta no processo — gate quebrado é gate que ninguém roda
**Branch:** version9
**Origem:** validação de `documentacao/bugs/done/2026-10-05-padkit-kotlinx-datetime-java-time-android-7.md`

## Sintoma

`./gradlew :lemuroid-app:lintFreeBundleDebug ktlintCheck --continue` (árvore de 2026-10-05, `dd46d57` +
correção do padkit, que só mexe em `.kts`/docs) termina em `BUILD FAILED`:

- **lint:** `Lint found 5 errors, 1 warnings (32 errors, 59 warnings filtered by baseline)`.
  Os 5 erros são `NewApi` em `CrashTelemetry.kt:259–264` (`ApplicationExitInfo#getReason`, `getPss`,
  `getRss`, `getProcessName`, `getTimestamp` — API 30). O warning é `ChromeOsAbiSupport` no `splits`
  do `lemuroid-app/build.gradle.kts`.
- **ktlint:** `:lemuroid-app:ktlintMainSourceSetCheck` (todas as linhas acusadas em
  `StreamingRomsManager.kt`) e `:retrograde-app-shared:ktlintMainSourceSetCheck`
  (`ManifestQuickLoader.kt`). Os `ktlintKotlinScriptCheck` (raiz e `:lemuroid-app`) passam.

## Causa provável (a confirmar ao corrigir)

- **lint:** as linhas 259–264 do `CrashTelemetry` entraram no `2c02594` (2026-09-30), depois do
  `lint-baseline.xml` (`9190756`, 2026-09-24). São falso positivo da mesma família já congelada:
  `reportOneExit` é privada e só é chamada por `reportPastExits`, que sai cedo com
  `SDK_INT < Build.VERSION_CODES.R`; o lint não propaga a guarda do chamador.
- **ktlint:** o `d0c76aa` ("expand catalogs", 2026-10-04) mexeu nos dois arquivos. O baseline do ktlint
  casa por número de linha (ver o comentário no fim do `lemuroid-app/build.gradle.kts`), então
  deslocar linhas transforma passivo congelado em violação "nova" — e pode haver violação nova de fato.

## O que fazer

1. lint: preferir guarda local (`@RequiresApi(Build.VERSION_CODES.R)` em `reportOneExit`) a regenerar o
   baseline — remove o falso positivo na origem. Só então `lintFreeBundleDebug` de novo.
2. ktlint: separar o que é deslocamento (regenerar baseline do módulo **depois** de conferir) do que é
   violação nova do `d0c76aa` (corrigir).
3. Rodar os dois gates antes de fechar qualquer mudança — a regra do pitfall 12/ktlint do `CLAUDE.md`.
