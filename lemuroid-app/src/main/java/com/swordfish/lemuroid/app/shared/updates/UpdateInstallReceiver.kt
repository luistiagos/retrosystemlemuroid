package com.swordfish.lemuroid.app.shared.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.utils.android.startActivitySafely
import com.swordfish.lemuroid.common.displayToast
import timber.log.Timber

class UpdateInstallReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_SESSION_ID = "com.swordfish.lemuroid.app.shared.updates.EXTRA_SESSION_ID"
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId =
            intent.getIntExtra(
                PackageInstaller.EXTRA_SESSION_ID,
                intent.getIntExtra(EXTRA_SESSION_ID, -1),
            )

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Timber.d("UpdateInstallReceiver: pending user action (session=$sessionId)")
                @Suppress("DEPRECATION")
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Uma exceção aqui mata o processo pelo BroadcastReceiver: em firmware de TV sem UI de
                // instalador a confirmação simplesmente não abre. Em vez de falhar em silêncio, avisamos
                // a UI para orientar o usuário.
                val started = confirmIntent != null && context.startActivitySafely(confirmIntent)
                if (!started) {
                    val userMessage = context.getString(R.string.update_error_no_installer)
                    reportFailure(context, sessionId, status, userMessage)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Timber.d("UpdateInstallReceiver: install succeeded (session=$sessionId)")
                UpdateInstallEvents.emit(UpdateInstallResult.Success(sessionId))
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Timber.d("UpdateInstallReceiver: install aborted by user (session=$sessionId message=$message)")
                UpdateInstallEvents.emit(UpdateInstallResult.Aborted(sessionId))
            }
            else -> {
                Timber.w("UpdateInstallReceiver: install failed (session=$sessionId status=$status message=$message)")
                val userMessage = formatFailureMessage(context, status, message)
                reportFailure(context, sessionId, status, userMessage)
            }
        }
    }

    private fun reportFailure(
        context: Context,
        sessionId: Int,
        status: Int,
        userMessage: String,
    ) {
        val observed = UpdateInstallEvents.hasObservers()
        UpdateInstallEvents.emit(UpdateInstallResult.Failed(sessionId, status, userMessage))
        if (!observed) {
            context.displayToast(userMessage, Toast.LENGTH_LONG)
        }
    }

    private fun formatFailureMessage(
        context: Context,
        status: Int,
        rawMessage: String?,
    ): String =
        when (classifyInstallFailure(status)) {
            InstallFailureKind.STORAGE -> context.getString(R.string.update_error_install_storage)
            InstallFailureKind.CONFLICT -> context.getString(R.string.update_error_install_conflict)
            InstallFailureKind.INCOMPATIBLE -> context.getString(R.string.update_error_install_incompatible)
            InstallFailureKind.INVALID -> context.getString(R.string.update_error_install_invalid)
            InstallFailureKind.BLOCKED -> context.getString(R.string.update_error_install_blocked)
            InstallFailureKind.GENERIC -> {
                val detail = rawMessage?.trim().orEmpty()
                if (detail.isNotEmpty()) {
                    context.getString(R.string.update_error_install_generic, status, detail)
                } else {
                    context.getString(R.string.update_error_install_generic_no_detail, status)
                }
            }
        }
}
