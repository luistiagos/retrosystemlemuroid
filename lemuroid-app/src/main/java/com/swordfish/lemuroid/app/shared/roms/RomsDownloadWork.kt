package com.swordfish.lemuroid.app.shared.roms

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.swordfish.lemuroid.app.mobile.shared.NotificationsManager
import com.swordfish.lemuroid.app.utils.android.createSyncForegroundInfo

class RomsDownloadWork(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val notificationsManager = NotificationsManager(applicationContext)
        setForeground(
            createSyncForegroundInfo(
                NotificationsManager.ROMS_DOWNLOAD_NOTIFICATION_ID,
                notificationsManager.downloadingRomsNotification(),
            ),
        )

        return try {
            RomsDownloadManager(applicationContext).doDownload { phase, progress ->
                setProgress(workDataOf(KEY_PHASE to phase, KEY_PROGRESS to progress))
            }
            Result.success()
        } catch (e: CancellationException) {
            // Re-throw so WorkManager knows this was a cooperative cancellation,
            // not a genuine failure — prevents a false FAILED state in the UI.
            throw e
        } catch (e: Throwable) {
            // If the Job was already cancelled (e.g. the user tapped Cancel, which calls
            // cancelDownload() + deleteRecursively() concurrently), an IOException from
            // the deleted files can arrive before CancellationException reaches the next
            // suspension point. Treat this as a cancellation, not a permanent failure, so
            // WorkManager reports CANCELLED and the UI doesn't show a spurious error card.
            if (!coroutineContext.isActive) throw CancellationException("Work cancelled", e)
            val msg = "${e.javaClass.simpleName}: ${e.message ?: "(no message)"}"
            // Clear the started flag so the init-block in RomsDownloadManager does not
            // re-enqueue work on every subsequent app open after a permanent failure.
            // The user must explicitly tap "Try again" to restart.
            RomsDownloadManager(applicationContext).clearDownloadStarted()
            Result.failure(workDataOf(KEY_ERROR to msg))
        }
    }

    companion object {
        const val KEY_PHASE = "phase"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        const val PHASE_DOWNLOADING = "downloading"
        const val PHASE_EXTRACTING = "extracting"
        const val UNIQUE_WORK_ID = "RomsDownloadWork"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_ID,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RomsDownloadWork>().build(),
            )
        }
    }
}
