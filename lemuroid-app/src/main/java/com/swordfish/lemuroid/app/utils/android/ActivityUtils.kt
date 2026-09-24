package com.swordfish.lemuroid.app.utils.android

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import timber.log.Timber

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

/**
 * Mostra a Activity sobre a tela de bloqueio e acende o display, sem assumir que o aparelho tem a
 * API que faz isso.
 *
 * `Activity.setShowWhenLocked`/`setTurnScreenOn` só existem a partir da API 27 (8.1). Com
 * `minSdkVersion = 21` a chamada direta vira `NoSuchMethodError` fatal em todo Android 5.0–8.0: foi
 * assim que *todo* jogo parou de abrir em TV Box e aparelho antigo quando as duas linhas entraram
 * no `onCreate` do `GameActivity` (regressão de 2026-09-03).
 *
 * O guard por `SDK_INT` sozinho não basta neste app: as TV Box baratas anunciam Android 9/11
 * rodando 7.1 de verdade (ver pitfall 7 no CLAUDE.md), e nelas o teste passa enquanto o
 * `framework.jar` continua sem o método. Por isso o `NoSuchMethodError` também é capturado. Nos
 * dois caminhos de falha a Activity cai nas flags de janela equivalentes — depreciadas na API 27,
 * mas é justamente o aparelho pré-27 que as consome.
 */
fun Activity.setShowWhenLockedCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        try {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            return
        } catch (e: NoSuchMethodError) {
            Timber.w(e, "setShowWhenLocked ausente com SDK_INT=${Build.VERSION.SDK_INT}; usando flags de janela")
        }
    }

    @Suppress("DEPRECATION")
    window.addFlags(
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
    )
}
