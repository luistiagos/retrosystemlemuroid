# Pausar e retomar o único download da fila derruba o foreground service e o download retomado segue sem notificação nem wake lock

- **Origem:** achado lateral do pipeline em 2026-10-05-anr-downloadforegroundservice-createnotificationchannel-main-thread (execução 20261119-2228)

**Origem:** achado lateral do pipeline em `2026-10-05-anr-downloadforegroundservice-createnotificationchannel-main-thread`
(deduzido do código, não reproduzido no aparelho).

## O que se viu (código, base `19cacda`)

- `DownloadForegroundService::observeQueue` (`lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/DownloadForegroundService.kt`)
  considera "há trabalho" só com item `SAVING` ou `QUEUED`. Sem isso: `stopForegroundCompat()` + `stopSelf()`;
  o `onDestroy` solta o `PARTIAL_WAKE_LOCK`.
- `SaveQueueManager::pauseActive` põe o item ativo em `PAUSED`. Com um único item na fila, a lista fica sem
  `SAVING`/`QUEUED` → o serviço para e solta o wake lock.
- `SaveQueueManager::resumeActive` só chama `romOnDemandManager.resumeDownload()` e volta o item para `SAVING`.
  **Não chama `DownloadForegroundService.start`**: o `start` só acontece em `ensureProcessorRunning`, que retorna
  cedo porque o `processorJob` continua ativo (suspenso em `_isPaused.first { !it }` dentro de
  `RomOnDemandManager.downloadToFile`).
- Resultado esperado: depois de retomar, o download continua sem foreground service, sem notificação de progresso
  e sem wake lock — exatamente o cenário (tela apagada / Doze, app em segundo plano) que o serviço existe para
  proteger (`docs/download_background.md`).

## Como confirmar

App aberto, um único jogo grande na fila (PSX/Dreamcast ≥ 200 MB); abrir a fila, pausar, retomar; a notificação
de download não volta (`adb shell dumpsys activity services app.retrogamesystem.debug` sem
`DownloadForegroundService`); apagar a tela 5 min e ver se o download avança.

## Correção provável

`resumeActive` chamar `DownloadForegroundService.start(appContext)` (ou o serviço considerar `PAUSED` como trabalho e
mostrar "pausado"). Fica fora do escopo do ANR: não muda nada no laço de Binder na main thread.
