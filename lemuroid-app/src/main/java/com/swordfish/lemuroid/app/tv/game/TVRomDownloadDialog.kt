package com.swordfish.lemuroid.app.tv.game

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.DialogInterface
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.roms.RomOnDemandManager
import com.swordfish.lemuroid.app.tv.shared.TVCovers
import com.swordfish.lemuroid.app.tv.shared.TVTypography
import com.swordfish.lemuroid.app.utils.games.GameUtils
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.launch

/**
 * Save dialog of the TV: confirmation, progress (blocking, with Cancel) and error. Redesign 3.2: the three
 * dialogs share one body ([R.layout.tv_rom_download_dialog]: cover, game, system, message) and the
 * [R.style.LemuroidTVDialog] theme; the flow and the buttons are the same as before.
 */
class TVRomDownloadDialog(
    private val activity: FragmentActivity,
    private val romOnDemandManager: RomOnDemandManager,
) {
    // The builders get the theme id itself: `Builder(context)` resolves `?android:alertDialogTheme` of the
    // context (the Leanback one), not the theme of a wrapper. The wrapper only inflates the body.
    private val themedContext = ContextThemeWrapper(activity, R.style.LemuroidTVDialog)

    fun show(
        game: Game,
        onComplete: (Game) -> Unit,
    ) {
        val body = DialogBody(game)
        body.message.text = activity.getString(R.string.rom_download_dialog_message)

        AlertDialog.Builder(activity, R.style.LemuroidTVDialog)
            .setView(body.root)
            .setPositiveButton(R.string.rom_download_dialog_confirm) { _, _ ->
                startDownload(game, onComplete)
            }
            .setNegativeButton(R.string.rom_download_dialog_cancel, null)
            .show()
            .styleButtons()
    }

    private fun startDownload(
        game: Game,
        onComplete: (Game) -> Unit,
    ) {
        val body = DialogBody(game)
        val statusText = body.message
        val progressBar = body.progress
        statusText.text = activity.getString(R.string.rom_download_dialog_downloading, 0)
        progressBar.visibility = View.VISIBLE

        val dialog =
            AlertDialog.Builder(activity, R.style.LemuroidTVDialog)
                .setView(body.root)
                .setCancelable(false)
                .setNegativeButton(R.string.rom_download_dialog_cancel) { _, _ ->
                    romOnDemandManager.cancelActiveDownload()
                }
                .show()
                .styleButtons()

        activity.lifecycleScope.launch {
            val result =
                romOnDemandManager.downloadRom(game) { progress ->
                    activity.runOnUiThread {
                        progressBar.isIndeterminate = progress == 0f
                        if (!progressBar.isIndeterminate) {
                            progressBar.progress = (progress * 100).toInt()
                        }
                        statusText.text =
                            activity.getString(
                                R.string.rom_download_dialog_downloading,
                                (progress * 100).toInt(),
                            )
                    }
                }

            if (dialog.isShowing) dialog.dismiss()

            when (result) {
                is RomOnDemandManager.DownloadResult.Success -> onComplete(result.game)
                is RomOnDemandManager.DownloadResult.NotFound ->
                    showError(
                        game,
                        activity.getString(R.string.rom_download_dialog_not_found),
                    )
                is RomOnDemandManager.DownloadResult.Failure ->
                    showError(
                        game,
                        activity.getString(R.string.rom_download_dialog_error, result.message),
                    )
            }
        }
    }

    private fun showError(
        game: Game,
        message: String,
    ) {
        // O download leva minutos: se o usuário saiu da tela nesse meio-tempo, `show()` pega um token
        // já morto e a BadTokenException derruba o app. Ver SafeToast.
        if (activity.isFinishing || activity.isDestroyed) return

        val body = DialogBody(game)
        body.message.text = message

        AlertDialog.Builder(activity, R.style.LemuroidTVDialog)
            .setView(body.root)
            .setPositiveButton(android.R.string.ok, null)
            .show()
            .styleButtons()
    }

    /** Outfit on the buttons; they only exist after `show()`. */
    private fun AlertDialog.styleButtons(): AlertDialog {
        listOf(DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL)
            .forEach { TVTypography.apply(getButton(it), TVTypography.Weight.MEDIUM) }
        return this
    }

    // A dialog body has no parent to inflate into: AlertDialog.setView takes the detached view.
    @SuppressLint("InflateParams")
    private inner class DialogBody(game: Game) {
        val root: View = LayoutInflater.from(themedContext).inflate(R.layout.tv_rom_download_dialog, null)
        val message: TextView = root.findViewById(R.id.tv_dialog_message)
        val progress: ProgressBar = root.findViewById(R.id.tv_dialog_progress)

        init {
            val heading = root.findViewById<TextView>(R.id.tv_dialog_heading)
            val title = root.findViewById<TextView>(R.id.tv_dialog_game_title)
            val system = root.findViewById<TextView>(R.id.tv_dialog_system)

            heading.setText(R.string.rom_download_dialog_title)
            title.text = game.title
            system.text = GameUtils.getGameSubtitle(activity, game)
            TVCovers.loadCover(game, root.findViewById<ImageView>(R.id.tv_dialog_cover))

            TVTypography.apply(heading, TVTypography.Weight.MEDIUM)
            TVTypography.apply(title, TVTypography.Weight.BOLD)
            TVTypography.apply(system, TVTypography.Weight.REGULAR)
            TVTypography.apply(message, TVTypography.Weight.REGULAR)
        }
    }
}
