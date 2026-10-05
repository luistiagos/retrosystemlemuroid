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
