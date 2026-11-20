---
tipo: implementacao
demandas: [2026-09-28-lowmemory-gameplay-ppsspp-foreground-service]
depende_de: []
---
# lowmemory: o relatório do processo principal diz qual serviço de primeiro plano o segurava

Demanda: `docs/bugs/aberto/2026-09-28-lowmemory-gameplay-ppsspp-foreground-service/` (ler a seção
"Planejamento (pipeline 20261119-2228)").

## Objetivo

46 dos 54 `lowmemory` desde outubro são do processo PRINCIPAL com `importance=125` (`FOREGROUND_SERVICE`), sem
jogo. O relatório não diz qual serviço estava em primeiro plano (`DownloadForegroundService` ou um Worker do
WorkManager), então não dá para saber se é o download de ROM ou um sync. Esta task cria o marcador e a leitura;
a task `lowmemory-fgs-marcadores` liga os call-sites.

## O que mudar

- `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/telemetry/TelemetryContext.kt`:
  - `markForegroundWork(context, tag: String)` / `clearForegroundWork(context, tag: String)`: gravam nas mesmas
    prefs (`prefs(context)`, `apply()`) o pid atual (`android.os.Process.myPid()`) e o conjunto de tags ativas.
    Se o pid gravado for outro (processo anterior morreu sem desmarcar), o conjunto recomeça só com a tag nova —
    é isso que evita marcador preso sem precisar limpar no boot. Nunca lançam (`try/catch Throwable`, como
    `setGameSession`).
  - `foregroundWorkForExit(context, processName, exitPid): String` → `"fgs=<tags ordenadas, separadas por +>"`
    quando o exit é do processo principal (`processName` sem `:`) e `exitPid` == pid gravado e há tag; senão `""`.
    Separar a decisão numa função pura `internal` (como `isSessionOfExit`) para o teste JVM não precisar de
    `Context`.
- `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/telemetry/CrashTelemetry.kt` ::`reportOneExit`:
  no `reportContext`, depois do `gameSessionForExit`, anexar `foregroundWorkForExit(context, info.processName,
  info.pid)` quando não vazio. Vale para qualquer `reason` (ANR/crash do principal também ganham). Fica dentro do
  `try` já existente.
- Comentário citando o id da demanda.

## Teste que prova

`lemuroid-app/src/test/java/com/swordfish/lemuroid/app/shared/telemetry/ForegroundWorkForExitTest.kt` (novo,
sobre a função pura): pid igual + `{download}` → `fgs=download`; pid diferente → `""`; processo `...:game` →
`""`; `{library, download}` → `fgs=download+library` (ordem estável); conjunto vazio → `""`. E a regra de
"pid novo recomeça o conjunto" (função pura de atualização). Os testes existentes `CrashTelemetryMemoryTest` e
`GameSessionForExitTest` seguem verdes.

## Plano de teste

- Offline: `build` do helper; suíte nova verde; linha de base 54/0/1 sem falha nova; `ktlintCheck` sem
  apontamento novo em `TelemetryContext.kt`/`CrashTelemetry.kt`.
- No aparelho esta task sozinha não muda nada visível (ninguém marca ainda); a prova E2E é da task
  `lowmemory-fgs-marcadores`.

## Correcao (pipeline 20261119-2228, rodada 1)

- `TelemetryContext::markForegroundWork` / `clearForegroundWork` (publicas, para a TASK-0008 chamar) →
  `updateForegroundWork`: `synchronized`, le `telemetry_fgs_pid` (Int) e `telemetry_fgs_tags` (String, tags
  ordenadas separadas por `+`) das mesmas prefs (`TelemetryReporter.PREFS_NAME`), grava pid atual + conjunto via
  `apply()`; `catch Throwable` engole tudo.
- Funcoes puras `internal`: `updatedForegroundTags(storedPid, storedTags, currentPid, tag, active)` (pid
  diferente → base vazia; depois `+tag`/`-tag`), `foregroundWorkOf(processName, exitPid, storedPid, storedTags)`
  (principal = nome nao vazio sem `:`; `storedPid > 0`, igual ao `exitPid`, conjunto nao vazio → `fgs=a+b`
  ordenado; senao `""`) e `parseForegroundTags`.
- `TelemetryContext::foregroundWorkForExit(context, processName, exitPid)` — le as prefs e delega; nunca lanca.
- `CrashTelemetry::reportOneExit` — depois do `gameSessionForExit`, anexa `; fgs=...` quando nao vazio, dentro
  do `try` existente; vale para qualquer `reason`.
- Teste novo `ForegroundWorkForExitTest` (8 casos: pid igual, pid diferente, `:game`/null, ordem estavel,
  vazio/pid 0, pid novo recomeca, marcar/desmarcar no mesmo pid, parse).
- Plano de teste sem ajuste. Nota para a TASK-0008: tag nao pode conter `+` (e o separador).

## Estado

- Feito (commit b026c50): codigo + teste. `build --bug TASK-0007` → BUILD SUCCESSFUL (8m26s); testes 109/0 falhas/1
  pulado, `ForegroundWorkForExitTest` 8/8; `gradlew :lemuroid-app:ktlintCheck` → exit 0, nenhum apontamento nos
  arquivos tocados. Proximo: `enqueue-test --bug TASK-0007` (teste: so offline + campos existentes; E2E e da TASK-0008).
