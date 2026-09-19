package com.swordfish.lemuroid.app.utils.android

import android.app.Notification
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo

fun createSyncForegroundInfo(
    notificationId: Int,
    notification: Notification,
): ForegroundInfo {
    // DATA_SYNC has no OS-imposed timeout and remains valid through Android 15 (API 35).
    // SHORT_SERVICE (API 34+) is killed by the system after a hard 3-minute cap — it must
    // never back an indeterminate-duration job like a ROMs download or a library sync
    // (RomsDownloadWork, StreamingRomsWork, LibraryIndexWork, CoreUpdateWork, SaveSyncWork all
    // call into this). If a future Android version drops DATA_SYNC support, callers should
    // guard their own setForeground() call rather than have this function silently swap in a
    // type with a timeout unfit for the job.
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else -> ForegroundInfo(notificationId, notification)
    }
}
