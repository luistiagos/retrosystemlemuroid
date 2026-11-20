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

## Estado

- 2026-11-20: análise da implementação registrada (seção abaixo). Falta: código (`DownloadNotificationThrottle.kt`,
  `DownloadForegroundService::observeQueue`/`publishQueueNotification`), teste `DownloadNotificationThrottleTest`,
  `build --bug TASK-0006`, ktlint, `## Correcao`, `enqueue-test --bug TASK-0006`.

## Análise da implementação (dev, pipeline 20261119-2228, 2026-11-20)

### Símbolos abertos

- `DownloadForegroundService` (arquivo inteiro, 207 l.): `scope` em `Dispatchers.Main`; `observeQueue` (l. 86–104) é
  coletor único; `updateNotification` (l. 131–139) tira `FLAG_ONGOING_EVENT` quando `foregroundPromotionFailed` e
  engole erro do `notify` (`runCatching`); `onStartCommand` monta a notificação e chama `startForeground`, e no
  `ForegroundServiceStartNotAllowedException` chama `showRegularNotification` (liga `foregroundPromotionFailed`, notify
  comum na main) + `stopSelfResult`; `onTimeout` (API 35+) = `pauseActive` + `stopForegroundCompat` + `stopSelfResult`;
  `onDestroy` = `scope.cancel()` + solta o wake lock.
- `NotificationsManager::romQueueNotification` (l. 142–180): mostra título (ou "fila"), texto "mais N" (título e
  `queuedCount > 0`), "baixando P%" (só título) ou "aguardando" (sem título); barra determinada só com título e
  `progress > 0f`, percentual `(progress * 100).toInt()`. Além do canal, faz `PendingIntent.getActivity` — **outro
  Binder** (AMS) por montagem: por isso a montagem inteira vai para o IO, não só o `notify`.
- `SaveQueueManager`: `SaveQueueState { QUEUED, SAVING, PAUSED, SAVED, ERROR }`; `SaveQueueEntry(fileName, gameId,
  title, coverUrl, fileUri, systemId, state, progress = 0f, errorMessage)` (classes de topo, sem Android: usáveis no
  teste JVM). `ensureProcessorRunning` (l. 204) só chama `DownloadForegroundService.start` quando o processador **não**
  está rodando: cada instância do serviço recebe um único start e morre quando a fila esvazia.
- `docs/bugs/concluido/2026-09-02-foreground-service-datasync-timeout/...` (l. 124): a notificação comum do fallback sem
  foreground é intencional ("tenta exibir uma notificação comum") — a correção não pode apagá-la.

### Provas

- `python -I -c` com `struct` (float32): `percent / 100f` reconvertido por `(progress * 100).toInt()` dá
  `[53, 59]` errados (mostraria 52% e 58%). Logo, o estado decide **se** publica; a publicação usa o `progress` cru
  da emissão publicada (desenho idêntico ao de antes).

### Decisões

1. **Corrida `notify` no IO × parada do serviço: as duas guardas, não uma.** `@Volatile stopping` liga no ramo "fila
   vazia", no `onTimeout` e no `onDestroy`, **antes** do `stopForeground`/`cancel`. No IO: (a) `stopping` já ligado →
   não monta nem publica; (b) depois que o `notify` volta, se o que foi postado é *ongoing* e `stopping` ligou no meio
   → `NotificationManagerCompat.cancel(ROMS_DOWNLOAD_NOTIFICATION_ID)`. Só (a) deixa a janela entre a checagem e o
   `system_server` processar o `enqueue` — que é exatamente o caso de IPC lento do ANR; só (b) faria Binder à toa
   depois de parar. O `cancel` sai depois que o `notify` retornou, então chega ao `NotificationManagerService` depois
   do `enqueue` do mesmo app; se o `stopForeground` da main chegar depois do `cancel`, também remove: o estado final é
   "sem notificação" nas duas ordens. Um `cancel` do app não remove notificação de serviço em foreground
   (`NotificationManagerService` recusa `FLAG_FOREGROUND_SERVICE` vindo do app), então não apaga a de uma instância
   nova. As duas checagens ficam **dentro** do `withContext(IO)`: o `scope.cancel()` do `onDestroy` não interrompe o
   Binder, mas faria a continuação pular código depois do `withContext`.
2. **`stopping` não é desligado.** Cada ponto que o liga é seguido da morte da instância (`stopSelf()`; no
   `onTimeout`, `stopSelfResult` do único start que a instância recebe; `onDestroy`). Desligar no `onStartCommand`
   abriria uma janela de publicação numa instância já derrubada.
3. **Corrida nova com o fallback sem foreground:** antes, tudo na main, o notify comum do `showRegularNotification`
   sempre vinha por último. Com o IO, um `notify` *ongoing* montado antes de `foregroundPromotionFailed` ligar pode
   chegar depois. Depois do `notify`, se o postado é *ongoing* e `foregroundPromotionFailed` ligou, republica a mesma
   notificação sem o flag (`updateNotification` já tira) — fica a notificação comum, como o fallback quer (essa
   checagem vem antes da de `stopping`: com as duas ligadas, a comum fica).
4. `foregroundPromotionFailed` vira `@Volatile` (lido no IO).
5. Valor final de uma janela de 1 s: aceito sem publicação "de cauda" — durante o download o próximo tick vem em
   milissegundos; no fim do item muda `title`/`queuedCount` (publica na hora) ou a fila esvazia (a notificação sai).
   Efeito visível: o 100% pode não aparecer antes da notificação sumir.
6. Relógio: `SystemClock.elapsedRealtime()` (monotônico; o de parede pode andar para trás).

### Hipóteses descartadas

- Montar a notificação a partir do estado (`percent / 100f`): erra 53 e 59 (prova acima).
- Lock entre o IO e a main em volta do `notify`: a main esperaria o Binder do IO — o ANR de volta.
- `launch` por emissão no IO: publicações concorrentes e fora de ordem; o ramo "fila vazia" correria junto com um
  `notify`.
