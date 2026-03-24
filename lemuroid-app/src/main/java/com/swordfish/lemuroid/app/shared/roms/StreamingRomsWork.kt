package com.swordfish.lemuroid.app.shared.roms

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.swordfish.lemuroid.app.mobile.shared.NotificationsManager
import com.swordfish.lemuroid.app.utils.android.createSyncForegroundInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class StreamingRomsWork(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val notificationsManager = NotificationsManager(applicationContext)
        val manager = StreamingRomsManager(applicationContext)
        return try {
            setForeground(
                createSyncForegroundInfo(
                    NotificationsManager.ROMS_DOWNLOAD_NOTIFICATION_ID,
                    notificationsManager.downloadingRomsNotification(),
                ),
            )
            withContext(Dispatchers.IO) {
                manager.doStreamingDownload { progress, currentFile, downloaded, total ->
                    setProgress(
                        workDataOf(
                            KEY_PROGRESS to progress,
                            KEY_CURRENT_FILE to currentFile,
                            KEY_DOWNLOADED_FILES to downloaded,
                            KEY_TOTAL_FILES to total,
                        )
                    )
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!coroutineContext.isActive) throw CancellationException("Work cancelled", e)
            val msg = "${e.javaClass.simpleName}: ${e.message ?: "(no message)"}"
            manager.clearDownloadStarted()
            Result.failure(workDataOf(KEY_ERROR to msg))
        }
    }

    companion object {
        const val KEY_PROGRESS = "streaming_progress"
        const val KEY_CURRENT_FILE = "streaming_current_file"
        const val KEY_DOWNLOADED_FILES = "streaming_downloaded_files"
        const val KEY_TOTAL_FILES = "streaming_total_files"
        const val KEY_ERROR = "streaming_error"
        const val UNIQUE_WORK_ID = "StreamingRomsWork"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_ID,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<StreamingRomsWork>().build(),
            )
        }
    }
}
