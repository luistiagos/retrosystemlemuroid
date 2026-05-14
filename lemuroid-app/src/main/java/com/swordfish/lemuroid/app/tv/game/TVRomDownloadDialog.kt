package com.swordfish.lemuroid.app.tv.game

import androidx.appcompat.app.AlertDialog
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.roms.RomOnDemandManager
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.launch

class TVRomDownloadDialog(
    private val activity: FragmentActivity,
    private val romOnDemandManager: RomOnDemandManager,
) {
    fun show(game: Game, onComplete: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.rom_download_dialog_title)
            .setMessage(
                activity.getString(R.string.rom_download_dialog_message) + "\n\n" + game.title,
            )
            .setPositiveButton(R.string.rom_download_dialog_confirm) { _, _ ->
                startDownload(game, onComplete)
            }
            .setNegativeButton(R.string.rom_download_dialog_cancel, null)
            .show()
    }

    private fun startDownload(game: Game, onComplete: () -> Unit) {
        val density = activity.resources.displayMetrics.density
        val padding = (24 * density).toInt()

        val titleView = TextView(activity).apply {
            text = game.title
            setTextAppearance(android.R.style.TextAppearance_Medium)
        }
        val statusText = TextView(activity).apply {
            text = activity.getString(R.string.rom_download_dialog_downloading, 0)
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
            addView(titleView)
            addView(statusText)
            addView(progressBar)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.rom_download_dialog_title)
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton(R.string.rom_download_dialog_cancel) { _, _ ->
                romOnDemandManager.cancelActiveDownload()
            }
            .show()

        activity.lifecycleScope.launch {
            val result = romOnDemandManager.downloadRom(game) { progress ->
                activity.runOnUiThread {
                    progressBar.isIndeterminate = progress == 0f
                    if (!progressBar.isIndeterminate) {
                        progressBar.progress = (progress * 100).toInt()
                    }
                    statusText.text = activity.getString(
                        R.string.rom_download_dialog_downloading,
                        (progress * 100).toInt(),
                    )
                }
            }

            if (dialog.isShowing) dialog.dismiss()

            when (result) {
                is RomOnDemandManager.DownloadResult.Success -> onComplete()
                is RomOnDemandManager.DownloadResult.NotFound -> showError(
                    activity.getString(R.string.rom_download_dialog_not_found),
                )
                is RomOnDemandManager.DownloadResult.Failure -> showError(
                    activity.getString(R.string.rom_download_dialog_error, result.message),
                )
            }
        }
    }

    private fun showError(message: String) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.rom_download_dialog_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
