package com.swordfish.lemuroid.app.shared.roms

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.swordfish.lemuroid.app.mobile.shared.NotificationsManager
import com.swordfish.lemuroid.app.utils.android.isForegroundServiceStartNotAllowed
import dagger.android.AndroidInjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
    private var foregroundPromotionFailed = false

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
        } catch (exception: RuntimeException) {
            if (!exception.isForegroundServiceStartNotAllowed()) throw exception

            Timber.w(exception, "DownloadForegroundService: foreground promotion was not allowed")
            showRegularNotification(notification)
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun observeQueue() {
        scope.launch {
            saveQueueManager.entries.collect { entries ->
                val hasWork =
                    entries.any {
                        it.state == SaveQueueState.SAVING || it.state == SaveQueueState.QUEUED
                    }
                if (!hasWork) {
                    Timber.d("DownloadForegroundService: queue empty, stopping self")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                val saving = entries.firstOrNull { it.state == SaveQueueState.SAVING }
                val queued = entries.count { it.state == SaveQueueState.QUEUED }
                updateNotification(buildNotification(saving?.title, saving?.progress ?: 0f, queued))
            }
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
        stopForeground(STOP_FOREGROUND_REMOVE)
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
