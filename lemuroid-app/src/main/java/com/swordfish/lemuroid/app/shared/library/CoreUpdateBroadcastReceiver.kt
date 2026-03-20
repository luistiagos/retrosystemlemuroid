package com.swordfish.lemuroid.app.shared.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CoreUpdateBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context?,
        intent: Intent?,
    ) {
        val ctx = context ?: return
        val coreId = intent?.getStringExtra(EXTRA_CORE_ID)
        LibraryIndexScheduler.scheduleCoreUpdate(ctx.applicationContext, coreId)
    }

    companion object {
        const val EXTRA_CORE_ID = "extra_core_id"
    }
}
