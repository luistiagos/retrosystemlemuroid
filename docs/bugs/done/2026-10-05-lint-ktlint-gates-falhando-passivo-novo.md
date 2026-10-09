# [BUG] `lintFreeBundleDebug` e `ktlintCheck` voltaram a falhar — passivo novo de 30/09 e 04/10

**Data:** 2026-10-05
**Status:** Aberto (registrado de passagem; não corrigido)
**Severidade:** Baixa no app, alta no processo — gate quebrado é gate que ninguém roda
**Branch:** version9
**Origem:** validação de `docs/bugs/done/2026-10-05-padkit-kotlinx-datetime-java-time-android-7.md`

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

## Corre??o

- `CrashTelemetry.reportOneExit` declara `@RequiresApi(Build.VERSION_CODES.R)`. O ?nico
  chamador continua protegido pelo retorno antecipado abaixo da API 30, e os `try/catch`
  existentes preservam o comportamento best-effort em aparelhos com framework incompat?vel.
- Removidas as 11 entradas `NewApi` do baseline de lint referentes a esse m?todo, agora
  desnecess?rias. Nenhuma nova exce??o foi acrescentada.
- Baselines de ktlint realinhados somente nas entradas afetadas: `StreamingRomsManager`
  (+2 linhas), `ManifestQuickLoader` (+6 linhas, incluindo os coment?rios da v37 j? presentes
  na ?rvore) e `CrashTelemetry` (+1 linha pelo import). Cada linha realinhada foi comparada
  com o conte?do anterior no Git; regras, colunas e contagens foram preservadas.
- A valida??o encontrou tamb?m o deslocamento causado pela tradu??o de "ROM not found"
  em `SaveQueueManager` (`a8f994f`). O ramo alterado foi formatado com chaves; suas tr?s
  exce??es antigas foram removidas e apenas as linhas inalteradas seguintes foram
  realinhadas. A tradu??o e a l?gica da fila foram preservadas.

## Valida??o

- Primeira execu??o interrompida pelo encerramento inesperado do daemon Gradle.
- Reexecu??o com `--no-parallel --max-workers=2`: lint com **0 erros, 1 warning**
  (`ChromeOsAbiSupport`; 21 erros e 59 warnings filtrados pelo baseline). O ktlint apontou
  somente `SaveQueueManager`, corrigido em seguida.
- Valida??o final: `./gradlew :lemuroid-app:lintFreeBundleDebug ktlintCheck --continue
  --console=plain --no-parallel --max-workers=2` ? **BUILD SUCCESSFUL** (3m31s;
  329 tasks, 22 executadas e 307 up-to-date). Ambos os gates habilitados.
- `git diff --check` sem erros. Log local em `build/reports/lint-ktlint/final.log`.

## Li??o

O baseline do ktlint identifica viola??es por linha e coluna: at? coment?rios podem
reapresentar passivo antigo. Conferir o diff e a identidade das linhas antes de realinhar;
corrigir o trecho alterado, sem acrescentar supress?es. Para helpers de API nova chamados
sob guarda de vers?o, declarar o contrato com `@RequiresApi` e manter a prote??o de runtime.
