package com.swordfish.lemuroid.app.shared.roms

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.swordfish.lemuroid.app.mobile.shared.NotificationsManager
import com.swordfish.lemuroid.app.shared.telemetry.TelemetryContext
import com.swordfish.lemuroid.app.utils.android.ForegroundWorkTag
import com.swordfish.lemuroid.app.utils.android.isForegroundServiceStartNotAllowed
import dagger.android.AndroidInjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that keeps ROM downloads alive when the screen turns off.
 *
 * Android can suspend or kill background coroutines during Doze mode. By calling
 * startForeground() we promote the process to a foreground service, which is exempt
 * from Doze and cannot be killed by the system while the notification is shown.
 *
 * Additionally, a PARTIAL_WAKE_LOCK is held for the duration of the download to
 * prevent the CPU from sleeping during network I/O.
 *
 * Lifecycle:
 *  - Started by SaveQueueManager when the first item is enqueued.
 *  - Observes SaveQueueManager.entries; auto-stops when the queue has no more
 *    QUEUED or SAVING items (self-managing, no explicit stop needed from outside).
 *  - WakeLock is acquired in onCreate and released in onDestroy.
 */
class DownloadForegroundService : Service() {
    @Inject lateinit var saveQueueManager: SaveQueueManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    // Lidos no Dispatchers.IO por publishQueueNotification; escritos na main.
    @Volatile private var foregroundPromotionFailed = false

    /**
     * Liga antes de o serviço sair do foreground (fila vazia, `onTimeout`, `onDestroy`) e não
     * desliga: nenhum desses pontos tem volta. A fila vazia chama `stopSelf()`; depois do
     * `onTimeout` o tempo do tipo `dataSync` acabou e o sistema para o serviço rebaixado
     * (javadoc de `Service.onTimeout(int, int)`).
     */
    @Volatile private var stopping = false

    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
        acquireWakeLock()
        observeQueue()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent == null) {
            Timber.w("DownloadForegroundService: ignoring null restart intent")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        // startForeground() must be called promptly in onStartCommand to satisfy
        // the 5-second ANR window imposed by Android on foreground service start.
        val notification = buildNotification(null, 0f, 0)
        try {
            ServiceCompat.startForeground(
                this,
                NotificationsManager.ROMS_DOWNLOAD_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            // Só a promoção que deu certo marca: é ela que põe o processo em FOREGROUND_SERVICE.
            TelemetryContext.markForegroundWork(applicationContext, ForegroundWorkTag.DOWNLOAD)
        } catch (exception: RuntimeException) {
            if (!exception.isForegroundServiceStartNotAllowed()) throw exception

            Timber.w(exception, "DownloadForegroundService: foreground promotion was not allowed")
            showRegularNotification(notification)
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    /**
     * Um coletor só, sequencial: enquanto uma publicação está no IO ele fica suspenso, o
     * `StateFlow` conflaciona as emissões, e o ramo "fila vazia" nunca corre junto com um
     * `notify` deste coletor.
     *
     * Antes, cada emissão (uma por `read()` do download) montava e postava a notificação na main
     * — Binder síncrono por tick, ANR com o IPC lento
     * (demanda 2026-10-05-anr-downloadforegroundservice-createnotificationchannel-main-thread).
     */
    private fun observeQueue() {
        scope.launch {
            var lastPublished: DownloadNotificationState? = null
            var lastPublishedAtMs = 0L
            saveQueueManager.entries.collect { entries ->
                val hasWork =
                    entries.any {
                        it.state == SaveQueueState.SAVING || it.state == SaveQueueState.QUEUED
                    }
                if (!hasWork) {
                    Timber.d("DownloadForegroundService: queue empty, stopping self")
                    stopping = true
                    stopForegroundCompat()
                    stopSelf()
                    return@collect
                }
                val state = DownloadNotificationState.from(entries)
                val nowMs = SystemClock.elapsedRealtime()
                if (!DownloadNotificationThrottle.shouldPublish(lastPublished, state, lastPublishedAtMs, nowMs)) {
                    return@collect
                }
                lastPublished = state
                lastPublishedAtMs = nowMs
                // O desenho usa o progresso cru: `percent / 100f` reconvertido erra (53% viraria 52%).
                val progress = entries.firstOrNull { it.state == SaveQueueState.SAVING }?.progress ?: 0f
                withContext(Dispatchers.IO) { publishQueueNotification(state, progress) }
            }
        }
    }

    /**
     * Monta e posta a notificação da fila. Roda no [Dispatchers.IO]: a montagem tem Binder
     * (`PendingIntent`, canal) e o `notify` também.
     *
     * O Binder não é cancelável e pode voltar depois que a main tirou o serviço do foreground
     * (`onTimeout`, `onDestroy`): uma notificação *ongoing* postada aí fica presa na cortina.
     * Por isso [stopping] é olhado antes (não posta) e depois (desfaz): só a checagem de antes
     * deixa a janela até o `system_server` processar o `enqueue`, que é justamente o caso de IPC
     * lento. O `cancel` sai depois do `notify` voltar, então chega depois dele; e um `cancel` do
     * app não remove a notificação de um serviço em foreground (a de uma instância nova).
     *
     * As checagens ficam aqui dentro, não depois do `withContext`: o `scope.cancel()` do
     * `onDestroy` não interrompe o Binder, mas faria a continuação pular o que viesse depois.
     */
    private fun publishQueueNotification(
        state: DownloadNotificationState,
        progress: Float,
    ) {
        if (stopping) return
        val notification = buildNotification(state.title, progress, state.queuedCount)
        if (stopping) return
        updateNotification(notification)
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) == 0) return
        when {
            // A promoção a foreground falhou enquanto este notify estava no ar: o fallback quer a
            // notificação comum (updateNotification tira o ongoing), não esta.
            foregroundPromotionFailed -> updateNotification(notification)
            stopping ->
                runCatching {
                    NotificationManagerCompat.from(this)
                        .cancel(NotificationsManager.ROMS_DOWNLOAD_NOTIFICATION_ID)
                }
        }
    }

    /**
     * `Service.stopForeground(int)` é da API 24 e o `minSdkVersion` é 21: chamada crua vira
     * `NoSuchMethodError` em Android 5.0–6.0 — e o `catch (RuntimeException)` deste arquivo não
     * pega `Error`. `ServiceCompat` escolhe entre a sobrecarga nova e a booleana da API 5; o
     * `catch` extra cobre o aparelho que mente a versão (pitfall 12).
     *
     * Existe de propósito num só lugar: o arquivo já teve o mesmo `stopForeground` guardado num
     * ponto e cru no outro.
     *
     * Desmarca também o trabalho em primeiro plano da telemetria (fila vazia e `onTimeout` saem
     * do foreground por aqui; o `onDestroy` desmarca de novo, sem efeito se já desmarcou).
     */
    private fun stopForegroundCompat() {
        TelemetryContext.clearForegroundWork(applicationContext, ForegroundWorkTag.DOWNLOAD)
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: NoSuchMethodError) {
            Timber.w(e, "stopForeground(int) ausente com SDK_INT=${Build.VERSION.SDK_INT}")
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(
        gameTitle: String?,
        progress: Float,
        queuedCount: Int,
    ): Notification = NotificationsManager(applicationContext).romQueueNotification(gameTitle, progress, queuedCount)

    private fun updateNotification(notification: Notification) {
        if (foregroundPromotionFailed) {
            notification.flags = notification.flags and Notification.FLAG_ONGOING_EVENT.inv()
        }
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(NotificationsManager.ROMS_DOWNLOAD_NOTIFICATION_ID, notification)
        }
    }

    private fun showRegularNotification(notification: Notification) {
        foregroundPromotionFailed = true
        updateNotification(notification)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock =
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lemuroid:RomDownload").also {
                it.setReferenceCounted(false)
                it.acquire(60 * 60 * 1000L) // 1h safety timeout — prevents runaway lock if onDestroy is skipped
            }
        Timber.d("DownloadForegroundService: WakeLock acquired")
    }

    override fun onDestroy() {
        stopping = true
        TelemetryContext.clearForegroundWork(applicationContext, ForegroundWorkTag.DOWNLOAD)
        scope.cancel()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        Timber.d("DownloadForegroundService: destroyed, WakeLock released")
    }

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return

        Timber.w(
            "DownloadForegroundService: foreground service timed out (type=%d), pausing queue",
            fgsType,
        )
        saveQueueManager.pauseActive()
        stopping = true
        stopForegroundCompat()
        stopSelfResult(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DownloadForegroundService::class.java),
                )
            } catch (exception: RuntimeException) {
                if (!exception.isForegroundServiceStartNotAllowed()) throw exception

                Timber.w(exception, "DownloadForegroundService: foreground start was not allowed")
                val notification =
                    NotificationsManager(context.applicationContext)
                        .romQueueNotification(null, 0f, 0)
                        .apply { flags = flags and Notification.FLAG_ONGOING_EVENT.inv() }
                runCatching {
                    NotificationManagerCompat.from(context).notify(
                        NotificationsManager.ROMS_DOWNLOAD_NOTIFICATION_ID,
                        notification,
                    )
                }.onFailure {
                    Timber.w(it, "DownloadForegroundService: regular notification could not be shown")
                }
            }
        }
    }
}
