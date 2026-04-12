package com.swordfish.lemuroid.app.utils.android

import android.app.Notification
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo

fun createSyncForegroundInfo(
    notificationId: Int,
    notification: Notification,
): ForegroundInfo {
    // Android 16 removed support for DATA_SYNC FGS type. Use SHORT_SERVICE on API 34+.
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else -> ForegroundInfo(notificationId, notification)
    }
}
