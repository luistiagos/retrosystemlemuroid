package com.swordfish.lemuroid.app.mobile.shared

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.game.GameActivity
import com.swordfish.lemuroid.app.shared.library.CoreUpdateBroadcastReceiver
import com.swordfish.lemuroid.app.shared.library.LibraryIndexBroadcastReceiver
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.app.mobile.feature.main.MainActivity

class NotificationsManager(private val applicationContext: Context) {
    fun gameRunningNotification(game: Game?): Notification {
        createDefaultNotificationChannel()

        val intent = Intent(applicationContext, GameActivity::class.java)
        val contentIntent =
            PendingIntent.getActivity(
                applicationContext,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val title =
            game?.let {
                applicationContext.getString(R.string.game_running_notification_title, game.title)
            } ?: applicationContext.getString(R.string.game_running_notification_title_alternative)

        val builder =
            NotificationCompat.Builder(applicationContext, DEFAULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_lemuroid_tiny)
                .setContentTitle(title)
                .setContentText(applicationContext.getString(R.string.game_running_notification_message))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setVibrate(null)
                .setSound(null)
                .setContentIntent(contentIntent)

        return builder.build()
    }

    fun libraryIndexingNotification(): Notification {
        createDefaultNotificationChannel()

        val broadcastIntent = Intent(applicationContext, LibraryIndexBroadcastReceiver::class.java)
        val broadcastPendingIntent: PendingIntent =
            PendingIntent.getBroadcast(
                applicationContext,
                0,
                broadcastIntent,
                PendingIntent.FLAG_IMMUTABLE,
            )

        val builder =
            NotificationCompat.Builder(applicationContext, DEFAULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_lemuroid_tiny)
                .setContentTitle(applicationContext.getString(R.string.library_index_notification_title))
                .setContentText(applicationContext.getString(R.string.library_index_notification_message))
                .setProgress(100, 0, true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(
                    NotificationCompat.Action(
                        null,
                        applicationContext.getString(R.string.cancel),
                        broadcastPendingIntent,
                    ),
                )

        return builder.build()
    }

    fun installingCoresNotification(): Notification {
        createDefaultNotificationChannel()

        val broadcastIntent = Intent(applicationContext, CoreUpdateBroadcastReceiver::class.java)
        val broadcastPendingIntent: PendingIntent =
            PendingIntent.getBroadcast(
                applicationContext,
                0,
                broadcastIntent,
                PendingIntent.FLAG_IMMUTABLE,
            )

        val builder =
            NotificationCompat.Builder(applicationContext, DEFAULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_lemuroid_tiny)
                .setContentTitle(
                    applicationContext.getString(
                        com.swordfish.lemuroid.ext.R.string.installing_core_notification_title,
                    ),
                )
                .setContentText(
                    applicationContext.getString(
                        com.swordfish.lemuroid.ext.R.string.installing_core_notification_message,
                    ),
                )
                .setProgress(100, 0, true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(
                    NotificationCompat.Action(
                        null,
                        applicationContext.getString(R.string.cancel),
                        broadcastPendingIntent,
                    ),
                )

        return builder.build()
    }

    fun saveSyncNotification(): Notification {
        createDefaultNotificationChannel()

        val builder =
            NotificationCompat.Builder(applicationContext, DEFAULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_lemuroid_tiny)
                .setContentTitle(applicationContext.getString(R.string.save_sync_notification_title))
                .setContentText(applicationContext.getString(R.string.save_sync_notification_message))
                .setProgress(100, 0, true)
                .setPriority(NotificationCompat.PRIORITY_LOW)

        return builder.build()
    }

    /**
     * Notification for the on-demand ROM download queue (SaveQueueManager).
     * Shows the current game being downloaded with a deterministic progress bar.
     *
     * @param gameTitle  Title of the ROM being downloaded, or null when the queue is
     *                   starting up and no item is active yet.
     * @param progress   Download progress in [0.0, 1.0]. Ignored when gameTitle is null.
     * @param queuedCount Number of items still waiting in QUEUED state (excluding the active one).
     */
    fun romQueueNotification(gameTitle: String?, progress: Float, queuedCount: Int): Notification {
        createDownloadNotificationChannel()

        val mainIntent = Intent(applicationContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            applicationContext, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = gameTitle
            ?: applicationContext.getString(R.string.notification_rom_queue_title)
        val progressPct = (progress * 100).toInt()
        val text = when {
            gameTitle != null && queuedCount > 0 ->
                applicationContext.getString(R.string.notification_rom_queue_more, queuedCount)
            gameTitle != null ->
                applicationContext.getString(R.string.notification_rom_queue_downloading, progressPct)
            else ->
                applicationContext.getString(R.string.notification_rom_queue_waiting)
        }

        val builder = NotificationCompat.Builder(applicationContext, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lemuroid_tiny)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)

        if (gameTitle != null && progress > 0f) {
            builder.setProgress(100, progressPct, false)
        } else {
            builder.setProgress(100, 0, true)
        }

        return builder.build()
    }

    fun downloadingRomsNotification(): Notification {
        createDownloadNotificationChannel()

        val builder =
            NotificationCompat.Builder(applicationContext, DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_lemuroid_tiny)
                .setContentTitle(applicationContext.getString(R.string.notification_download_roms_title))
                .setContentText(applicationContext.getString(R.string.notification_download_roms_message))
                .setProgress(100, 0, true)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)

        return builder.build()
    }

    private fun createDefaultNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = applicationContext.getString(R.string.notification_channel_name)
            val importance = NotificationManager.IMPORTANCE_MIN
            val mChannel = NotificationChannel(DEFAULT_CHANNEL_ID, name, importance)
            val notificationManager =
                ContextCompat.getSystemService(applicationContext, NotificationManager::class.java)
            notificationManager?.createNotificationChannel(mChannel)
        }
    }

    // Dedicated channel for the ROM download foreground service. Uses IMPORTANCE_LOW so
    // the notification icon appears in the status bar — required on OEM firmwares (Motorola,
    // Xiaomi etc.) that treat IMPORTANCE_MIN as a non-persistent notification and kill the
    // foreground service when the app is backgrounded.
    private fun createDownloadNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = applicationContext.getString(R.string.notification_download_roms_title)
            val mChannel = NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                name,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }
            val notificationManager =
                ContextCompat.getSystemService(applicationContext, NotificationManager::class.java)
            notificationManager?.createNotificationChannel(mChannel)
        }
    }

    companion object {
        const val DEFAULT_CHANNEL_ID = "DEFAULT_CHANNEL_ID"
        const val DOWNLOAD_CHANNEL_ID = "DOWNLOAD_CHANNEL_ID"

        const val LIBRARY_INDEXING_NOTIFICATION_ID = 1
        const val SAVE_SYNC_NOTIFICATION_ID = 2
        const val GAME_RUNNING_NOTIFICATION_ID = 3
        const val CORE_INSTALL_NOTIFICATION_ID = 4
        const val ROMS_DOWNLOAD_NOTIFICATION_ID = 5
    }
}
