---
fecha_por: prova
tasks: [TASK-0005, TASK-0006]
---
# [BUG] ANR na `MainActivity` — `DownloadForegroundService` bloqueia main thread com `createNotificationChannels` no IPC Binder a cada progresso de download

- **Detectado em:** 2026-10-02 23:17 (telemetria de produção)
- **Origem:** telemetria `retrogamesystem/anr` (`libc.so::__ioctl+8`)
- **Errors (serviço):** 9303 (1 ocorrência)
- **Classe:** ANR / fail
- **Complexidade:** media — Mover a criação do notification channel para inicialização única ou background e evitar chamadas Binder IPC a cada emissão de progresso na main thread.
- **Reincidência:** primeira vez detectado (Realme RMX5020, Android 16 / sdk 36, app 1.17.23)

---

## Sintoma

O usuário estava com o aplicativo em primeiro plano (`MainActivity`) durante downloads de ROMs da fila (`SaveQueueManager`). Ao perder ou alterar o foco da janela (ou interação do usuário), o Android disparou um ANR após 5 segundos de espera pelo evento de input:

```
Subject: Input dispatching timed out (72375ce app.retrogamesystem/com.swordfish.lemuroid.app.mobile.feature.main.MainActivity is not responding. Waited 5000ms for FocusEvent(hasFocus=false)).
```

O processo foi interrompido com ANR com a thread `main` travada em chamada Binder síncrona:

```
DALVIK THREADS (59):
"main" prio=5 tid=1 Native
  native: #00 pc 000f8588  /apex/com.android.runtime/lib64/bionic/libc.so (__ioctl+8)
  native: #01 pc 000974ec  /apex/com.android.runtime/lib64/bionic/libc.so (ioctl+156)
  native: #02 pc 00068d84  /system/lib64/libbinder.so (android::IPCThreadState::transact+7684)
  native: #03 pc 0006db90  /system/lib64/libbinder.so (android::BpBinder::transact+512)
  native: #04 pc 001f4bc0  /system/lib64/libandroid_runtime.so (android_os_BinderProxy_transact+416)
  at android.os.BinderProxy.transactNative(Native method)
  at android.os.BinderProxy.transact(BinderProxy.java:685)
  at android.app.INotificationManager$Stub$Proxy.createNotificationChannels(INotificationManager.java:3957)
  at android.app.NotificationManager.createNotificationChannels(NotificationManager.java:1269)
  at android.app.NotificationManager.createNotificationChannel(NotificationManager.java:1257)
  at com.swordfish.lemuroid.app.mobile.shared.NotificationsManager.createDownloadNotificationChannel(NotificationsManager.kt:...)
  at com.swordfish.lemuroid.app.mobile.shared.NotificationsManager.romQueueNotification(NotificationsManager.kt:143)
  at com.swordfish.lemuroid.app.shared.roms.DownloadForegroundService.buildNotification(DownloadForegroundService.kt:129)
  at com.swordfish.lemuroid.app.shared.roms.DownloadForegroundService.updateNotification(DownloadForegroundService.kt:137)
  at com.swordfish.lemuroid.app.shared.roms.DownloadForegroundService$observeQueue$1$1.emit(DownloadForegroundService.kt:101)
```

## Causa raiz

Confirmada no código-fonte em:
1. [DownloadForegroundService.kt:44](file:///c:/projects/lemuroid/Lemuroid/lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/DownloadForegroundService.kt#L44):
   O serviço instancia `scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)`.
   O método `observeQueue()` faz:
   ```kotlin
   scope.launch {
       saveQueueManager.entries.collect { entries ->
           ...
           updateNotification(buildNotification(saving?.title, saving?.progress ?: 0f, queued))
       }
   }
   ```
2. [NotificationsManager.kt:142-143](file:///c:/projects/lemuroid/Lemuroid/lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/shared/NotificationsManager.kt#L142-L143):
   ```kotlin
   fun romQueueNotification(gameTitle: String?, progress: Float, queuedCount: Int): Notification {
       createDownloadNotificationChannel()
       ...
   ```
   A função `romQueueNotification` chama `createDownloadNotificationChannel()` **em todas as chamadas**.
3. O `saveQueueManager.entries` é um `StateFlow` que emite atualizações frequentes de progresso de download da ROM. Como o coletor do flow roda em `Dispatchers.Main`, cada tick de progresso executa uma transação Binder síncrona IPC (`INotificationManager.createNotificationChannels`) para o `system_server`. Se o sistema operacional estiver sob carga de I/O de escrita do download ou concorrência com notificações, o Binder IPC bloqueia a thread `main` por mais de 5 segundos, culminando em ANR fatal.

## Como reproduzir

1. Iniciar o download de uma ROM volumosa via fila de downloads (`SaveQueueManager`).
2. Com a notificação de progresso sendo atualizada repetidamente na `main` thread, trocar o foco da janela ou pressionar o botão Home/Recentes.
3. Sob latência de IPC do Android, a `main` thread é retida em `ioctl` do Binder bloqueando o processamento do `FocusEvent`.

## Correção proposta

1. Em [NotificationsManager.kt](file:///c:/projects/lemuroid/Lemuroid/lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/shared/NotificationsManager.kt):
   Garantir que `createDownloadNotificationChannel()` seja executado apenas uma única vez na inicialização ou através de uma flag guard `if (!channelCreated)`.
2. Em [DownloadForegroundService.kt](file:///c:/projects/lemuroid/Lemuroid/lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/DownloadForegroundService.kt):
   Garantir throttling ou despachar a montagem e disparo da notificação fora do fluxo estrito de renderização da main thread se necessário.

## Planejamento (pipeline 20261119-2228, 2026-11-19)

**Veredito:** corrigir — duas tasks de implementação: `canal-notificacao-uma-vez-por-processo` (o canal é criado uma vez por processo) e `notificacao-download-fora-da-main-com-throttle` (a notificação de progresso só é publicada quando muda o que aparece, no máximo ~1/s, com o Binder fora da main).
**Complexidade:** alta — fila de download (`DownloadForegroundService` / `SaveQueueManager`) é "alta" pelo critério de `docs/bugs/aberto/README.md`. Por task: baixa (canal) e media (throttle + troca de thread com a corrida do `stopForeground`).
**Severidade:** medio — o ANR fatal só vem quando o IPC do `system_server` atrasa (1 ocorrência: Realme RMX5020 / Android 16 / 1.17.23), mas o laço de Binder na main existe em **todo** download de **todo** usuário (Android 8+) enquanto a Início está aberta.
**Fecha por:** `prova` — o mecanismo (Binder por tick na main) se mede nesta máquina com `am trace-ipc` durante um download pela UI. O ANR em si depende da latência do sistema e não se reproduz sob demanda. Sem `casador`: a `message` do ANR na telemetria é `ANR: Input dispatching timed out ...`, genérica, e casaria qualquer ANR de input; o frame `createNotificationChannels` está só no log anexo.
**Arquivos a alterar (reserva da onda):**
- `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/shared/NotificationsManager.kt` — guarda por processo nos dois `create*NotificationChannel` + correção do passivo ktlint do arquivo (task 1)
- `lemuroid-app/config/ktlint/baseline.xml` — sai o bloco de `NotificationsManager.kt` (task 1)
- `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/DownloadForegroundService.kt` — `observeQueue` com throttle e `notify` em `Dispatchers.IO`; guarda contra `notify` depois de parar (task 2)
- `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/DownloadNotificationThrottle.kt` (novo, task 2)
- `lemuroid-app/src/test/java/com/swordfish/lemuroid/app/shared/roms/DownloadNotificationThrottleTest.kt` (novo, task 2)

### Causa raiz (símbolos abertos)

- `DownloadForegroundService::scope` (l. 44) — `CoroutineScope(SupervisorJob() + Dispatchers.Main)`: tudo o que o serviço coleta roda na main do processo principal, a mesma da `MainActivity`.
- `DownloadForegroundService::observeQueue` (l. 86–104) — coletor único de `saveQueueManager.entries`. A cada emissão com trabalho: `buildNotification(...)` + `updateNotification(...)`. Sem trabalho: `stopForegroundCompat()` + `stopSelf()` e `return@collect`. Não há filtro de mudança nem limite de taxa.
- `DownloadForegroundService::buildNotification` (l. 125–129) — `NotificationsManager(applicationContext).romQueueNotification(...)`: instância nova a cada chamada (uma flag de instância não serviria).
- `DownloadForegroundService::updateNotification` (l. 131–139) — `NotificationManagerCompat.notify` dentro de `runCatching` (o erro é engolido, nada propaga). É o **segundo** Binder por tick (`enqueueNotificationWithTag`).
- `NotificationsManager::romQueueNotification` (l. 142) — chama `createDownloadNotificationChannel()` sempre, antes de montar.
- `NotificationsManager::createDownloadNotificationChannel` (l. 212–227) e `::createDefaultNotificationChannel` (l. 197–206) — `if (SDK_INT >= O)` → `NotificationManager.createNotificationChannel` (Binder `createNotificationChannels`, o frame do ANR). Sem guarda e sem try (uma exceção propagaria ao chamador).
- `SaveQueueManager::processQueue` (l. 210–235) — o callback de progresso faz `_entries.update { ... copy(progress = p) }` a cada chamada; roda em `Dispatchers.IO`.
- `RomOnDemandManager::downloadToFile` (l. ~790–800) — `onProgress(bytesWritten / contentLength)` a cada `input.read(buffer)` (buffer de 256 KB; o `read` do OkHttp costuma devolver bem menos), ou seja, muitas emissões por segundo. O `StateFlow` conflaciona: durante o download inteiro, o coletor na main fica num laço contínuo de 2 Binder por volta.
- Outros chamadores de `NotificationsManager` (`GameService`, `CoreUpdateWork`, `LibraryIndexWork`, `RomsDownloadWork`, `StreamingRomsWork`, `SaveSyncWork`, `DownloadForegroundService.start`): uma notificação por execução; a guarda do canal não muda o que eles mostram.

### Provas (comando literal -> resultado)

- `grep -n "onProgress" lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/RomOnDemandManager.kt` -> l. 799 `onProgress(bytesWritten.toFloat() / contentLength.toFloat())` dentro do laço de `read`, um por chunk.
- `grep -rn "NotificationsManager(" --include=*.kt lemuroid-app/src` -> 7 sites; só o `DownloadForegroundService` chama por tick.
- `grep -n -A14 "NotificationsManager.kt" lemuroid-app/config/ktlint/baseline.xml` -> 14 apontamentos congelados (linhas 3, 142–165 e 215). Mexer em `createDownloadNotificationChannel` desloca a l. 215, por isso a task 1 corrige o passivo do arquivo e tira o bloco do baseline. `DownloadForegroundService.kt` não tem bloco.
- `lemuroid-app/build.gradle.kts:346` -> só `testImplementation("junit:junit:4.13.2")` (sem coroutines-test nem Robolectric). Por isso a lógica testável da task 2 vai para uma função pura.

### Hipóteses descartadas (e por quê)

- **Só a guarda do canal resolve.** Não: sobra o `notify` (Binder `enqueueNotificationWithTag`) por tick na main, chamada síncrona do mesmo tipo ao mesmo serviço. Além disso, o `system_server` limita a 5 enqueues/s por pacote e descarta o excedente: boa parte do trabalho na main é jogada fora.
- **Só trocar o `scope` do serviço para `Dispatchers.IO`.** Não: tira o Binder da main, mas mantém centenas de IPC/s, e `stopForeground`/`stopSelf` passariam a correr em paralelo com `notify` de outra thread, o que recria a notificação ongoing depois do `stopForeground(REMOVE)`. Só o trecho do `notify` muda de thread, num coletor sequencial (task 2).
- **Throttle no produtor (`SaveQueueManager`/`RomOnDemandManager`).** Descartado: a UI da fila e a TopBar usam o mesmo `entries` (o Compose já conflaciona por frame, sem Binder). Limitar ali muda a UI e mexe no download, área "alta", sem necessidade. O problema está só no consumidor que faz IPC.
- **Pré-criar os canais no `Application.onCreate`.** Desnecessário com a guarda por processo: a primeira criação acontece no `onStartCommand`, um Binder por start, que o `startForeground` exige de qualquer jeito.

### Tarefas

- [ ] `canal-notificacao-uma-vez-por-processo` (implementacao) — `NotificationsManager::createDownloadNotificationChannel` / `::createDefaultNotificationChannel` com flag por processo no `companion` + passivo ktlint do arquivo. Prova: `am trace-ipc` sem `createNotificationChannels` durante o download.
- [ ] `notificacao-download-fora-da-main-com-throttle` (implementacao) — `DownloadNotificationThrottle` (novo, puro) + `DownloadForegroundService::observeQueue` publicando só quando muda o que aparece, no máximo 1/s para o percentual, `notify` em `Dispatchers.IO` e nenhum `notify` depois de parar. Prova: `DownloadNotificationThrottleTest` + `am trace-ipc` (≤ ~35 `enqueueNotificationWithTag` em 30 s, fora da main) + a notificação some ao cancelar.

### Plano de teste

- **Offline (sem slot):** `build` do helper: `DownloadNotificationThrottleTest` verde e linha de base 54/0/1 mantida; `:lemuroid-app:ktlintCheck` sem apontamento nos arquivos tocados.
- **T2 reprodução como o usuário (E2E, no slot):** app debug aberto na Início, com Wi-Fi. Tocar num jogo do catálogo **não baixado** de PSX ou Dreamcast (≥ 200 MB) → confirmar o download → abrir a fila (ícone de download na barra) e deixar baixando. Com o download em curso: `adb -s <serial> shell am trace-ipc start`; esperar 30 s; `adb -s <serial> shell am trace-ipc stop --dump-file /data/local/tmp/ipc.txt`; `adb -s <serial> pull /data/local/tmp/ipc.txt`. No bloco do processo `app.retrogamesystem.debug`, contar `createNotificationChannels` e `enqueueNotificationWithTag` e ler o stack (main = `Looper.loop`/`HandlerContext`; IO = `DefaultDispatcher-worker`). Também procurar no `adb logcat` da janela por `Package enqueue rate is` com `package=app.retrogamesystem.debug`. Prints da cortina aos ~10 s e ~20 s. No fim, cancelar o download pela fila (X) e tirar print da cortina 5 s depois.
  - **Sinal do defeito:** dezenas a centenas de `createNotificationChannels` **e** de `enqueueNotificationWithTag` em 30 s, com stack em `DownloadForegroundService$observeQueue` sobre a main; possivelmente `Shedding events` do `NotificationService`.
  - **Esperado com o fix:** 0 `createNotificationChannels`; ≤ ~35 `enqueueNotificationWithTag`, em thread de IO; nenhum `Shedding events` do pacote; percentual subindo entre os dois prints; a notificação some ao cancelar e não volta.
  - O ANR propriamente dito não dá para provocar (depende da latência de IPC do sistema). O que se prova é a remoção, da main, do Binder por tick, que é a causa.
- **Controle positivo:** mesmo roteiro com o APK da base (`git checkout --detach <base>` + `build`, manual, seção Teste (E2E)): os contadores do `trace-ipc` têm de vir altos e com stack na main. Se o `Shedding events` não aparecer no aparelho, vale só o `trace-ipc` (registrar os números das duas versões).
- **T3 regressão direcionada:** (1) download pequeno (NES) até o fim: a notificação some e o jogo aparece como baixado e abre; (2) dois jogos na fila: o título da notificação troca assim que o segundo começa; (3) pausar e retomar pela fila: mesmo comportamento da base (ver o achado lateral `2026-11-19-retomar-download-pausado-sem-foreground-service`, que não é regressão desta mudança); (4) as notificações do canal padrão (reescanear a biblioteca em Ajustes; abrir um jogo → "jogo em execução") continuam aparecendo; (5) `logcat` do PID sem `AndroidRuntime`/`FATAL`/ANR na janela.

### Sessões seguintes

- dev: `canal-notificacao-uma-vez-por-processo` effort medium, ultrathink não — guarda mecânica + formatação ktlint. `notificacao-download-fora-da-main-com-throttle` effort xhigh, ultrathink sim — ANR (manual) e corrida entre o `notify` no IO e o `stopForeground`/`onTimeout`/`onDestroy` na main.
- teste: canal effort medium, ultrathink não. Throttle effort high, ultrathink não — ler o dump do `trace-ipc` e distinguir a thread pelo stack pede cuidado, mas o critério é numérico.

### Achado lateral

- `docs/bugs/aberto/2026-11-19-retomar-download-pausado-sem-foreground-service/` — pausar o único download da fila e retomar deixa o download sem foreground service, sem notificação e sem wake lock (`SaveQueueManager::resumeActive` não chama `DownloadForegroundService.start`). Fora desta execução.
