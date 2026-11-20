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
