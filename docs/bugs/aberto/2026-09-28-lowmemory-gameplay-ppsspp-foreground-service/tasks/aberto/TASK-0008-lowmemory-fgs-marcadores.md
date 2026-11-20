---
tipo: implementacao
demandas: [2026-09-28-lowmemory-gameplay-ppsspp-foreground-service]
depende_de: [TASK-0007]
---
# lowmemory: DownloadForegroundService e Workers em foreground gravam o marcador de serviço ativo

Demanda: `docs/bugs/aberto/2026-09-28-lowmemory-gameplay-ppsspp-foreground-service/` (seção "Planejamento").
Depende de `lowmemory-fgs-leitura` (usa `TelemetryContext.markForegroundWork`/`clearForegroundWork`).

## Objetivo

Todo caminho que põe o processo principal em `FOREGROUND_SERVICE` marca a sua tag enquanto está em primeiro
plano e desmarca ao sair, para o próximo `lowmemory`/ANR do processo principal chegar com `fgs=<tag>`.

## O que mudar (abrir cada função inteira antes: retorno e caminho de erro)

- `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/DownloadForegroundService.kt`: tag
  `download`. Marcar em `onStartCommand` só depois do `ServiceCompat.startForeground` que deu certo (o ramo
  `isForegroundServiceStartNotAllowed` NÃO marca); desmarcar em `onDestroy` e onde o serviço sai do foreground
  (`stopForegroundCompat`, `onTimeout`). Atenção: este arquivo está reservado também por outras demandas desta
  execução (`2026-10-05-anr-downloadforegroundservice-...`, `2026-11-19-retomar-download-pausado-...`): faça o
  `sync` e trabalhe sobre o que elas integraram.
- `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/utils/android/WorkManagerUtils.kt`: um helper único
  para os Workers (ex.: `suspend fun <T> CoroutineWorker.inForeground(tag, info, block: suspend () -> T): T`
  ou par marcar/desmarcar) que chama `setForeground` com o `try/catch` que os workers já têm, marca só se o
  `setForeground` não lançou, e desmarca num `finally` (cobre `CancellationException`). Não pode mudar o
  comportamento atual de "continua sem notificação se `setForeground` falhar".
- Workers, cada um com sua tag: `CoreUpdateWork` (`core`), `LibraryIndexWork` (`library`), `SaveSyncWork`
  (`savesync`), `RomsDownloadWork` (`romsdownload`), `StreamingRomsWork` (`streaming`). Caminhos:
  `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/library/{CoreUpdateWork,LibraryIndexWork}.kt`,
  `.../shared/roms/{RomsDownloadWork,StreamingRomsWork}.kt`, `.../shared/savesync/SaveSyncWork.kt`.
- Nada roda no `:game`; nenhuma API acima do `minSdk` nova (`Process.myPid` é API 1).

## Teste que prova

- JVM: se o helper do `WorkManagerUtils` tiver lógica testável (marca só com sucesso, desmarca no `finally`),
  teste com lambdas falsas em `lemuroid-app/src/test/...`; senão, justificar no commit.
- E2E (aparelho do slot): Home ▸ jogo PSX/Dreamcast não baixado ▸ confirmar download; durante o download
  `adb shell run-as app.retrogamesystem.debug cat shared_prefs/<TelemetryReporter.PREFS_NAME>.xml` mostra a tag
  `download` e o pid de `adb shell pidof app.retrogamesystem.debug`; ao concluir (ou cancelar na fila) a tag
  some. Ajustes ▸ reescanear biblioteca → tag `library` durante o `LibraryIndexWork`, some ao terminar.

## Plano de teste

- Controle positivo: APK da base (`19cacda`), mesmo download: o arquivo de prefs não tem marcador.
- T3: download conclui e o "jogar agora?" aparece; pausar/retomar; reescanear biblioteca termina; nenhum crash
  ou ANR no `logcat` do PID; `ktlintCheck` sem apontamento novo nos arquivos tocados; `lintFreeBundleDebug`
  passa.

## Analise do dev (pipeline 20261119-2228, 2026-11-20)

Simbolos abertos (base `1ba98c9`, ja com TASK-0005 e TASK-0007 integradas):

- `TelemetryContext::markForegroundWork`/`clearForegroundWork` (TASK-0007) — `synchronized`, `apply()` nas
  `telemetry_prefs`, `catch Throwable`: nunca lancam. Conjunto por pid; tag nao pode ter `+`.
- `DownloadForegroundService::onStartCommand` — `ServiceCompat.startForeground` num `try`; o `catch` so engole
  `isForegroundServiceStartNotAllowed` (resto relanca). Pontos de saida do foreground: `observeQueue` (fila vazia →
  `stopForegroundCompat()` + `stopSelf()`), `onTimeout` (API 35+, `stopForegroundCompat()` + `stopSelfResult`) e
  `onDestroy`. Todos na main (o `scope` e `Dispatchers.Main`).
- Workers, caminho de erro do `setForeground` HOJE:
  - `CoreUpdateWork::doWork`, `LibraryIndexWork::doWork`, `SaveSyncWork::displayNotification`:
    `try { setForeground } catch (e: Exception) { Timber.w }` → seguem sem notificacao.
  - `RomsDownloadWork::doWork`, `StreamingRomsWork::doWork`: `setForeground` DENTRO do `try` geral → falha cai no
    `catch Throwable` → `Result.failure` (ou `CancellationException` se o job ja nao esta ativo). **Nao** seguem sem
    notificacao. O helper tem de preservar os dois comportamentos (tarefa diz: nao mudar comportamento) →
    parametro `onPromotionFailed: (Exception) -> Unit` (engole e loga, ou relanca).
  - `SaveSyncWork`: o `setForeground` esta em `displayNotification()`, separado do `sync`: o bloco marcado passa a
    envolver `displayNotification + sync`.
- Processo: `LemuroidApplication.workManagerConfiguration` nao define `defaultProcessName`; no bytecode do
  `work-runtime-2.9.0` (`ProcessUtils.isDefaultProcess`) o default e `applicationInfo.processName` = processo
  principal. `SystemForegroundService` e `DownloadForegroundService` sem `android:process`. `CoreUpdateBroadcastReceiver`
  sem `android:process`. Logo todo marcador e gravado pelo pid do principal (o que `foregroundWorkOf` espera).
- `CoroutineWorker.setForeground(ForegroundInfo)` e `public final suspend` (javap) → chamavel de extensao.

Decisoes:

- **Contagem por tag no helper dos Workers** (`ForegroundTagCounter`, em memoria, por processo): `CoreUpdateWork`
  urgente (`CORE_UPDATE_URGENT_WORK_ID`, REPLACE) e o de fundo (`CORE_UPDATE_WORK_ID`) rodam ao mesmo tempo com a
  mesma tag `core`; sem contagem, o que termina primeiro apagaria o marcador do outro. Marcar/desmarcar acontece
  dentro do lock da contagem (senao `release`→`acquire` concorrentes podiam terminar com o marcador apagado e o
  trabalho rodando). Processo morto zera a contagem junto com o pid — nada fica preso.
- Tags num lugar so (`ForegroundWorkTag` em `WorkManagerUtils.kt`): `download`, `core`, `library`, `savesync`,
  `romsdownload`, `streaming`.
- Servico: marca so depois do `startForeground` que deu certo; desmarca dentro de `stopForegroundCompat()` (cobre fila
  vazia e `onTimeout`) e em `onDestroy`. O ramo `isForegroundServiceStartNotAllowed` nao marca nem desmarca (se uma
  promocao anterior valeu, o servico segue em foreground ate o `onDestroy`).
- Custo na main do servico: um `apply()` de prefs pequenas no inicio e no fim do download (o `waitToFinish` do
  `ActivityThread` apos `onStartCommand`/`onDestroy` espera essa escrita). Mesmo padrao do `setGameSession`; mover
  para outra thread nao evita a espera (o `apply` cai antes do `waitToFinish` do mesmo jeito). Aceito.

Hipoteses descartadas:

- Worker rodando no `:game` gravaria pid errado e zeraria o conjunto do principal — descartada pelo bytecode acima.
- Um helper que sempre engole a falha do `setForeground` — mudaria `RomsDownloadWork`/`StreamingRomsWork` de
  "falha" para "segue sem notificacao"; fora do escopo.

Teste: `lemuroid-app/src/test/java/com/swordfish/lemuroid/app/utils/android/RunMarkedInForegroundTest.kt` sobre a
funcao pura `runMarkedInForeground` (lambdas falsas): sucesso marca antes e desmarca depois; falha engolida nao
marca e roda o bloco; falha relancada nao roda o bloco; bloco que lanca (inclusive `CancellationException`)
desmarca; dois donos da mesma tag marcam uma vez e desmarcam so no ultimo.
