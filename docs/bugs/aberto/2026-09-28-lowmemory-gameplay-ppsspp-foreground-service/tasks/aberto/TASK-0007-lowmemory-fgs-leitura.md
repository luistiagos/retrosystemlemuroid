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
