---
tipo: implementacao
demandas: [2026-10-05-anr-downloadforegroundservice-createnotificationchannel-main-thread]
depende_de: []
---
# Notificação de progresso do download: só quando muda o que aparece, no máximo ~1/s, e fora da main thread

## Objetivo

`DownloadForegroundService::observeQueue` coleta `SaveQueueManager.entries` num `CoroutineScope(SupervisorJob() + Dispatchers.Main)`
e, a cada emissão, monta a notificação (`NotificationsManager.romQueueNotification`) e chama
`NotificationManagerCompat.notify` — **duas** transações Binder síncronas na main thread por emissão.
`entries` emite a cada `read()` do corpo HTTP (`RomOnDemandManager.downloadToFile`, buffer de até 256 KB,
`onProgress(bytesWritten / contentLength)` por chunk → `SaveQueueManager.processQueue` faz `_entries.update`).
O `StateFlow` conflaciona, então durante o download inteiro a main fica num laço de Binder, e uma latência
de IPC de 5 s vira ANR (telemetria 9303). Além disso o `system_server` limita a 5 enqueues/s por pacote e
descarta o excedente (`NotificationManagerService`: "Package enqueue rate is … Shedding events") — o
trabalho na main é, em grande parte, jogado fora.

Depende da task `canal-notificacao-uma-vez-por-processo` só conceitualmente (arquivos diferentes, sem
ordem obrigatória).

## O que mudar

1. **Arquivo novo** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/DownloadNotificationThrottle.kt`
   — lógica pura (sem Android), testável na JVM:
   - `data class DownloadNotificationState(val title: String?, val percent: Int, val queuedCount: Int)` derivada das
     entries (`title`/`percent` do item `SAVING`, `percent = (progress * 100).toInt()`, `queuedCount` = `QUEUED`),
     exatamente os campos que `romQueueNotification` mostra.
   - decisão `shouldPublish(last: DownloadNotificationState?, next: DownloadNotificationState, lastPublishedAtMs: Long, nowMs: Long): Boolean`:
     `false` se `next == last`; `true` se mudou `title` ou `queuedCount` (troca de jogo / fila é imediata);
     se só o `percent` mudou, `true` apenas com `nowMs - lastPublishedAtMs >= 1000`. Intervalo como constante.
   - Ponto em aberto que o dev decide e registra: com só o `percent` mudando e o último tick dentro da janela
     de 1 s, o valor final de uma janela pode não ser publicado até o próximo tick; aceitável (o próximo
     tick chega em milissegundos durante o download, e o fim do item muda `title`/`queuedCount` ou esvazia a fila).
2. `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/DownloadForegroundService.kt`, `observeQueue`:
   - continua coletando num **único** coletor sequencial (não `launch` por emissão);
   - calcula o `DownloadNotificationState`, aplica `shouldPublish`; se publicar, faz `buildNotification` +
     `updateNotification` dentro de `withContext(Dispatchers.IO)` (o coletor suspende até o Binder voltar, a main
     fica livre). O ramo "fila vazia" (`stopForegroundCompat()` + `stopSelf()`) segue no contexto do coletor como hoje.
   - **Corrida a evitar (o ponto difícil da task):** um `notify` que volte do IO **depois** de
     `stopForegroundCompat(REMOVE)` recria a notificação ongoing de um serviço já parado — notificação presa.
     Com o coletor sequencial o ramo "fila vazia" nunca corre junto com um `notify` do próprio coletor; mas
     `onTimeout` (Android 15+) chama `stopForegroundCompat()` fora do coletor e `onDestroy` faz `scope.cancel()`
     com um `withContext(IO)` possivelmente no meio do Binder (não cancelável). Garantir que, se o serviço
     parou/está parando quando o `notify` retornar, a notificação é cancelada
     (`NotificationManagerCompat.cancel(ROMS_DOWNLOAD_NOTIFICATION_ID)`) ou que o `notify` não é feito (flag
     `@Volatile stopping` ligada em `onTimeout`/`onDestroy`/ramo vazio e checada imediatamente antes do `notify`, já
     no IO). Escrever no doc da demanda qual dos dois e por quê.
   - `foregroundPromotionFailed` passa a ser lido fora da main: `@Volatile`.
   - `onStartCommand` não muda: `startForeground` precisa da notificação já ali (é uma chamada por start).
   - `companion.start` (fallback sem foreground) não muda.
   - Comentário citando o id da demanda.
3. **Teste novo** `lemuroid-app/src/test/java/com/swordfish/lemuroid/app/shared/roms/DownloadNotificationThrottleTest.kt`
   (JUnit 4, como os vizinhos `RomsDirChoiceTest`): (a) primeira publicação (`last == null`) → `true`; (b) estado igual → `false`;
   (c) só percentual mudou, 200 ms depois → `false`; (d) só percentual, 1000 ms depois → `true`; (e) `title` mudou
   50 ms depois → `true`; (f) `queuedCount` mudou 50 ms depois → `true`; (g) derivação: entries com um `SAVING` em
   0.423 e dois `QUEUED` → `("X", 42, 2)`; sem `SAVING` → `title == null`. Simular 1.000 ticks de progresso em
   10 s (um a cada 10 ms) → no máximo ~11 publicações (prova numérica da taxa).

`DownloadForegroundService.kt` não tem passivo no `lemuroid-app/config/ktlint/baseline.xml` (conferido); os dois
arquivos novos nascem limpos no ktlint.

## Plano de teste da task

- **Offline:** o `build` do helper roda o `DownloadNotificationThrottleTest` (todos verdes) e a linha de base
  (54 testes / 0 falhas / 1 pulado) continua.
- **T2 (aparelho, pela UI, como o usuário):** app aberto na Início; tocar num jogo do catálogo **não baixado** de PSX
  ou Dreamcast (≥ 200 MB) → confirmar → abrir a fila (ícone de download na barra). Com o download em curso:
  `adb -s <serial> shell am trace-ipc start`; 30 s; `adb -s <serial> shell am trace-ipc stop --dump-file /data/local/tmp/ipc.txt`;
  `adb pull`. No bloco do processo `app.retrogamesystem.debug`, contar `enqueueNotificationWithTag` e olhar o stack.
  E `adb logcat -b system` / `logcat` da janela por `Package enqueue rate is` com `package=app.retrogamesystem.debug`.
  - **Sinal do defeito (base):** centenas de `enqueueNotificationWithTag` em 30 s com stack vindo de
    `DownloadForegroundService$observeQueue` sobre `Looper.loop`/`HandlerContext` (main), e o log
    `Package enqueue rate is … Shedding events. package=app.retrogamesystem.debug` do `NotificationService`.
  - **Esperado (fix):** ≤ ~35 `enqueueNotificationWithTag` em 30 s, stack em thread do `Dispatchers.IO`
    (`DefaultDispatcher-worker`), nenhum `Shedding events` do pacote; a notificação continua subindo o percentual
    (print da cortina aos ~10 s e ~20 s mostra percentuais diferentes).
  - Cancelar o download pela fila (X) no fim: a notificação **some** em ≤ 2 s e não volta (print da cortina 5 s depois).
- **Controle positivo:** mesmo roteiro com o APK da base (manual, seção Teste (E2E)); se o `Shedding events` não
  aparecer no aparelho, a contagem do `trace-ipc` é o sinal (registrar o número das duas versões).
- **T3 regressão:** (1) download que termina normalmente (jogo pequeno, ex.: NES): a notificação some ao fim e o
  jogo abre/aparece como baixado; (2) dois jogos na fila: o texto troca para o segundo título logo que ele começa
  (mudança de `title` publica sem esperar 1 s); (3) pausar e retomar pela fila: mesmo comportamento da base (com um único item, pausar para o serviço e
  retomar não o religa: é o achado lateral `2026-11-19-retomar-download-pausado-sem-foreground-service`, não regressão
  desta task);
  (4) `logcat` do PID sem `AndroidRuntime`/`FATAL`/ANR na janela.
