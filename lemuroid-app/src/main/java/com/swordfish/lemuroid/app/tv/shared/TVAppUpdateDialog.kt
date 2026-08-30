package com.swordfish.lemuroid.app.tv.shared

import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.updates.AppUpdateManager
import com.swordfish.lemuroid.common.displayToast
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Atualizacao in-app na interface de TV.
 *
 * O fluxo do celular vive no MainActivity em Compose; a TV usa fragments
 * leanback, entao aqui a mesma logica do [AppUpdateManager] e dirigida por
 * AlertDialogs comuns - o mesmo padrao do TVRomDownloadDialog.
 *
 * A build armeabi-v7a e distribuida justamente para Smart TV / TV box antiga:
 * sem isto esses aparelhos nunca ficariam sabendo de uma versao nova.
 */
class TVAppUpdateDialog(private val activity: FragmentActivity) {

    private val manager = AppUpdateManager(activity.applicationContext)

    /** Checagem ao abrir o app: silenciosa, no maximo 1x a cada 12h. */
    fun checkOnStartup() {
        if (!manager.shouldCheckNow()) return
        check(userInitiated = false)
    }

    /** "Verificar atualizacoes" nas opcoes: ignora throttle e versao dispensada. */
    fun checkManually() {
        activity.displayToast(R.string.update_checking)
        check(userInitiated = true)
    }

    private fun check(userInitiated: Boolean) {
        activity.lifecycleScope.launch {
            val info =
                try {
                    manager.checkForUpdate()
                } catch (e: Exception) {
                    // Na checagem automatica e silencioso de proposito: rede
                    // instavel nao pode virar popup toda vez que o app abre.
                    Timber.w(e, "TV update check failed")
                    if (userInitiated) {
                        showMessage(
                            activity.getString(R.string.update_error_title),
                            e.message ?: "",
                        )
                    }
                    return@launch
                }

            if (activity.isFinishing || activity.isDestroyed) return@launch

            if (info == null) {
                if (userInitiated) {
                    showMessage(
                        activity.getString(R.string.update_no_update_title),
                        activity.getString(R.string.update_no_update_message),
                    )
                }
                return@launch
            }

            // Usuario ja disse "Agora nao" para esta versao - nao insistir.
            // Mas se ele mesmo pediu a checagem, mostra de novo.
            if (!userInitiated && manager.isVersionSkipped(info.versionCode)) return@launch

            showUpdateDialog(info)
        }
    }

    private fun showUpdateDialog(info: AppUpdateManager.UpdateInfo) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_dialog_title, info.versionName))
            .setMessage(R.string.update_dialog_message)
            .setPositiveButton(R.string.update_dialog_yes) { _, _ -> startUpdate(info) }
            .setNegativeButton(R.string.update_dialog_no) { _, _ ->
                manager.skipVersion(info.versionCode)
            }
            .show()
    }

    private fun startUpdate(info: AppUpdateManager.UpdateInfo) {
        // Checa a permissao antes de baixar 100 MB que o sistema recusaria instalar.
        if (!manager.canInstallPackages()) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission_message)
                .setPositiveButton(R.string.update_permission_action) { _, _ ->
                    runCatching { activity.startActivity(manager.buildAllowInstallIntent()) }
                        .onFailure { Timber.w(it, "Could not open install-permission settings") }
                }
                .setNegativeButton(R.string.update_dialog_no, null)
                .show()
            return
        }

        val density = activity.resources.displayMetrics.density
        val padding = (24 * density).toInt()

        val statusText = TextView(activity).apply {
            text = activity.getString(R.string.update_downloading_message, 0)
        }
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = (8 * density).toInt() }
        }
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(statusText)
            addView(progressBar)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_downloading_title)
            .setView(layout)
            .setCancelable(false)
            .show()

        activity.lifecycleScope.launch {
            try {
                manager.downloadAndInstall(info) { progress ->
                    activity.runOnUiThread {
                        progressBar.isIndeterminate = progress == 0f
                        if (!progressBar.isIndeterminate) {
                            progressBar.progress = (progress * 100).toInt()
                        }
                        statusText.text = activity.getString(
                            R.string.update_downloading_message,
                            (progress * 100).toInt(),
                        )
                    }
                }
                if (dialog.isShowing) dialog.dismiss()
            } catch (e: Exception) {
                Timber.w(e, "TV update download failed")
                if (dialog.isShowing) dialog.dismiss()
                if (activity.isFinishing || activity.isDestroyed) return@launch
                showMessage(activity.getString(R.string.update_error_title), e.message ?: "")
            }
        }
    }

    private fun showMessage(title: String, message: String) {
        // O ramo de erro do `check()` chama isto antes do guard de isFinishing que existe no caminho
        // feliz — e a checagem é de rede, com segundos de janela para o usuário sair da tela.
        if (activity.isFinishing || activity.isDestroyed) return

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
