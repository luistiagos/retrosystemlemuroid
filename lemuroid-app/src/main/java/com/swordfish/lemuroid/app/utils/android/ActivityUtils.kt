package com.swordfish.lemuroid.app.utils.android

import android.app.Activity
import androidx.appcompat.app.AlertDialog

// TODO COMPOSE... How do they look in the post compose world?
fun Activity.displayErrorDialog(
    messageId: Int,
    actionLabelId: Int,
    action: () -> Unit,
) {
    displayErrorDialog(resources.getString(messageId), resources.getString(actionLabelId), action)
}

fun Activity.displayErrorDialog(
    message: String,
    actionLabel: String,
    action: () -> Unit,
) {
    // Quem chama isto costuma vir de coroutine (ExternalGameLauncherActivity, StorageFrameworkPicker):
    // a activity pode já estar fechando quando o trabalho termina, e `show()` com token morto lança
    // BadTokenException — que na main thread mata o processo. Ver SafeToast.
    if (isFinishing || isDestroyed) return

    AlertDialog.Builder(this)
        .setMessage(message)
        .setPositiveButton(actionLabel) { _, _ -> action() }
        .setCancelable(false)
        .show()
}
