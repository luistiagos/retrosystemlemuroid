package com.swordfish.lemuroid.app.shared.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import timber.log.Timber

/**
 * Receives PackageInstaller callbacks after the APK install session completes.
 * On API < 31 the session may need the user to confirm via a system activity —
 * this receiver starts that confirmation activity automatically.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Timber.d("UpdateInstallReceiver: status=$status message=$message")

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            confirmIntent?.let { context.startActivity(it) }
        }
    }
}
