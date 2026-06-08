# Download em Background (Tela Apagada)

## Problema

Antes desta feature, os downloads de ROMs na `SaveQueueManager` rodavam como uma simples coroutine sem nenhuma proteção de processo. O Android pode suspender ou matar processos em segundo plano durante o **Doze mode** — ativado quando a tela apaga e o dispositivo fica parado por alguns segundos. Resultado: o download era interrompido silenciosamente assim que o usuário apagava a tela.

---

## Como o Android Mata Processos em Background

O Android usa três mecanismos que precisam ser contornados juntos:

| Mecanismo do Android | O que faz | Como contornar |
|----------------------|-----------|----------------|
| **Doze mode** | Suspende network access e CPU de processos background | Foreground Service fica isento do Doze |
| **Process killing** | Mata processos sem foreground component quando precisa de RAM | Foreground Service protege o processo de ser morto |
| **CPU sleep** | Apaga a CPU quando não há wakelock ativo | `PARTIAL_WAKE_LOCK` mantém a CPU acordada |

Nenhum dos três sozinho é suficiente. O Foreground Service resolve os dois primeiros; o WakeLock resolve o terceiro.

---

## Solução Implementada

### Mecanismos usados

| Mecanismo | API Android | Por que é necessário |
|-----------|-------------|---------------------|
| **Foreground Service** (`startForeground`) | `android.app.Service` | Exibe notificação persistente; isenta o processo de Doze e impede que seja morto pelo sistema |
| **WakeLock (`PARTIAL_WAKE_LOCK`)** | `PowerManager.newWakeLock()` | Mantém a CPU acordada para que o I/O de rede (OkHttp) não seja suspenso; tela pode continuar apagada |
| **Coroutine em `Dispatchers.IO`** | (já existia em `RomOnDemandManager`) | Thread de download independente do ciclo de vida da Activity |

> **WifiLock não é usado.** Um Foreground Service com `foregroundServiceType="dataSync"` já instrui o sistema operacional a manter o rádio WiFi ativo durante a execução. `WifiManager.WIFI_MODE_FULL_HIGH_PERF` está depreciado desde API 31 e é no-op em dispositivos modernos (Android 12+).

---

## Arquitetura

### Fluxo de um download

```
Usuário toca em um ROM placeholder
  └── MainActivity → pendingDownloadGame
        └── SaveQueueManager.enqueue(game)
              ├── persiste item no Room (estado QUEUED)
              ├── adiciona em _entries (StateFlow)
              └── ensureProcessorRunning()
                    ├── DownloadForegroundService.start(context)   ← NOVO
                    └── lança processQueue() coroutine             ← já existia

processQueue() [Dispatchers.IO]
  └── para cada item QUEUED:
        ├── marca como SAVING no Room + StateFlow
        └── RomOnDemandManager.downloadRom(game) { progress → }
              ├── resolve URL via pythonanywhere endpoint
              ├── baixa via OkHttp com suporte a pause/resume
              └── retorna Success / NotFound / Failure

DownloadForegroundService [processo principal, Dagger Android]
  ├── onCreate()
  │     ├── AndroidInjection.inject(this)    → injeta SaveQueueManager
  │     ├── acquireWakeLock()                → PARTIAL_WAKE_LOCK por até 1h
  │     └── observeQueue()                  → coleta SaveQueueManager.entries
  │
  ├── onStartCommand()
  │     └── startForeground(ROMS_DOWNLOAD_NOTIFICATION_ID, notificação inicial)
  │
  ├── observeQueue() [collect em Main]
  │     ├── entry SAVING encontrada → updateNotification(título, progresso, qtd na fila)
  │     └── nenhum QUEUED/SAVING    → stopForeground() + stopSelf()
  │
  └── onDestroy()
        ├── scope.cancel()          → cancela o collect
        └── wakeLock.release()      → libera a CPU
```

### Auto-gerenciamento de ciclo de vida

O serviço **não precisa ser parado explicitamente** por nenhum outro componente. Ele observa o `StateFlow<List<SaveQueueEntry>>` do `SaveQueueManager` e chama `stopForeground() + stopSelf()` assim que não há mais itens em estado `QUEUED` ou `SAVING`. O WakeLock é liberado em `onDestroy()`.

---

## Injeção de Dependência (Dagger Android)

O projeto usa **Dagger Android** (não Hilt). A injeção no Service segue o padrão `AndroidInjection.inject(this)` chamado como **primeiro statement de `onCreate()`**, antes de `super.onCreate()`.

**Registro em `LemuroidApplicationModule.kt`:**
```kotlin
@ContributesAndroidInjector
abstract fun downloadForegroundService(): DownloadForegroundService
```

**No Service:**
```kotlin
override fun onCreate() {
    AndroidInjection.inject(this)   // injeta @Inject lateinit var saveQueueManager
    super.onCreate()
    acquireWakeLock()
    observeQueue()
}
```

---

## Notificação do Sistema

Canal: `DOWNLOAD_CHANNEL_ID` — `NotificationManager.IMPORTANCE_LOW`.

Usar `IMPORTANCE_LOW` (e não `IMPORTANCE_MIN`) é intencional: OEMs como Motorola e Xiaomi tratam `IMPORTANCE_MIN` como notificação não-persistente e podem matar o foreground service quando o app vai para background. `IMPORTANCE_LOW` aparece na status bar sem som — isso é suficiente para que o sistema respeite o foreground service.

| Estado da fila | Título da notificação | Texto | Barra de progresso |
|----------------|-----------------------|-------|--------------------|
| Iniciando (nenhum item ativo ainda) | "Downloading ROM…" | "Waiting to start…" | Indeterminada |
| Baixando, sem fila | Nome do jogo (ex: "Sonic") | "Downloading… 45%" | Determinística — 45% |
| Baixando, com mais itens na fila | Nome do jogo | "+ 3 more in queue" | Determinística |
| Fila esvazia | — | — | Notificação removida via `stopForeground(STOP_FOREGROUND_REMOVE)` |

Tocar na notificação abre a `MainActivity` via `PendingIntent` (FLAG_IMMUTABLE).

---

## WakeLock — Detalhes

```kotlin
val pm = getSystemService(POWER_SERVICE) as PowerManager
wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lemuroid:RomDownload").also {
    it.setReferenceCounted(false)
    it.acquire(60 * 60 * 1000L) // 1h safety timeout
}
```

| Detalhe | Explicação |
|---------|------------|
| `PARTIAL_WAKE_LOCK` | CPU acordada, tela pode apagar — o mínimo necessário para I/O de rede |
| `setReferenceCounted(false)` | Um único `release()` libera o lock, independente de quantos `acquire()` foram chamados |
| Timeout de 1h | Fallback de segurança: se `onDestroy()` for pulado por bug de OEM, o lock é liberado automaticamente pelo sistema após 1 hora |
| Liberado em `onDestroy()` | `wakeLock?.takeIf { it.isHeld }?.release()` — guard contra double-release |

---

## Permissões no AndroidManifest.xml

```xml
<!-- Obrigatório em Android 9+ para chamar startForeground() -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>

<!-- Obrigatório em Android 14+ para foregroundServiceType="dataSync" -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>

<!-- Obrigatório para PowerManager.newWakeLock() -->
<uses-permission android:name="android.permission.WAKE_LOCK"/>
```

**Declaração do serviço:**
```xml
<service
    android:name=".app.shared.roms.DownloadForegroundService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

---

## Arquivos Modificados

| Arquivo | O que mudou |
|---------|-------------|
| [`DownloadForegroundService.kt`](../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/DownloadForegroundService.kt) | **Arquivo novo** — Service completo com WakeLock, notificação dinâmica e auto-stop |
| [`SaveQueueManager.kt`](../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/SaveQueueManager.kt) | `ensureProcessorRunning()` adicionou `DownloadForegroundService.start(appContext)` antes de lançar a coroutine |
| [`NotificationsManager.kt`](../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/shared/NotificationsManager.kt) | Novo método `romQueueNotification(gameTitle, progress, queuedCount)` com barra determinística e intent para MainActivity |
| [`LemuroidApplicationModule.kt`](../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/LemuroidApplicationModule.kt) | `@ContributesAndroidInjector abstract fun downloadForegroundService()` registra o Service no grafo Dagger |
| [`AndroidManifest.xml`](../lemuroid-app/src/main/AndroidManifest.xml) | `FOREGROUND_SERVICE` permission + declaração do serviço com `foregroundServiceType="dataSync"` |
| [`strings.xml`](../lemuroid-app/src/main/res/values/strings.xml) | 4 novas strings: `notification_rom_queue_title/downloading/more/waiting` |

---

## O que NÃO mudou

- `RomOnDemandManager` — toda a lógica de download (OkHttp, pause/resume, retry, archive.org) permanece intacta.
- `SaveQueueManager.processQueue()` — lógica de fila (QUEUED → SAVING → SAVED/ERROR) permanece intacta.
- UI da TopBar e `SaveQueueModal` — sem alterações.
- `RomOnDemandManager.downloadToFile()` — o loop de leitura com `currentCoroutineContext().ensureActive()` e `_pausedFlow.first { !it }` continua funcionando normalmente dentro do Foreground Service.

---

## Referência

Padrão analisado no projeto **ARMSX2** (`DownloadForegroundService.java` + `RomDownloadManager.java` + `DownloadQueueManager.java`) e adaptado para a arquitetura Kotlin/Coroutines/Dagger Android do Lemuroid. A principal diferença: ARMSX2 usa um `Handler(Looper.getMainLooper())` + thread Java pura; o Lemuroid usa `StateFlow` + coroutines, tornando a observação da fila reativa e sem polling.
