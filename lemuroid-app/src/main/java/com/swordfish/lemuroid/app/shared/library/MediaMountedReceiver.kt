package com.swordfish.lemuroid.app.shared.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MediaMountedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MEDIA_MOUNTED) {
            LibraryIndexScheduler.scheduleManualLibrarySync(context.applicationContext)
        }
    }
}
