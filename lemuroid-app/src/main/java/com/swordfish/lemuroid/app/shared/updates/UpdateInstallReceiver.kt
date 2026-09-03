package com.swordfish.lemuroid.app.shared.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.swordfish.lemuroid.app.utils.android.startActivitySafely
import timber.log.Timber

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Timber.d("UpdateInstallReceiver: status=$status message=$message")

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Uma exceção aqui mata o processo pelo BroadcastReceiver: em firmware de TV sem UI de
            // instalador a confirmação simplesmente não abre, e a atualização fica para depois.
            confirmIntent?.let { context.startActivitySafely(it) }
        }
    }
}
