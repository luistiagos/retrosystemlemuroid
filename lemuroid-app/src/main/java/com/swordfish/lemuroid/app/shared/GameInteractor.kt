package com.swordfish.lemuroid.app.shared

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.shortcuts.ShortcutsGenerator
import com.swordfish.lemuroid.app.shared.game.GameLauncher
import com.swordfish.lemuroid.app.shared.main.BusyActivity
import com.swordfish.lemuroid.app.shared.roms.RomOnDemandManager
import com.swordfish.lemuroid.common.displayToast
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.launch
import java.io.File

class GameInteractor(
    private val activity: BusyActivity,
    private val retrogradeDb: RetrogradeDatabase,
    private val useLeanback: Boolean,
    private val shortcutsGenerator: ShortcutsGenerator,
    private val gameLauncher: GameLauncher,
    private val onPlaceholderGame: ((Game, (Game) -> Unit) -> Unit)? = null,
    // Only the TV UI wires this: its context menu (GameContextMenuListener) has no other way
    // to reach the deletion actions. The mobile UI drives RomOnDemandManager straight from
    // MainActivity, where the confirmation is a Compose AlertDialog.
    private val romOnDemandManager: RomOnDemandManager? = null,
) {
    fun onGamePlay(game: Game) {
        if (!ensureNotBusy()) {
            return
        }
        if (!ensureNotificationsPermissionAvailable()) {
            return
        }
        val placeholderHandler = onPlaceholderGame
        if (placeholderHandler != null && isGamePlaceholder(game)) {
            placeholderHandler(game) { downloadedGame ->
                gameLauncher.launchGameAsync(activity.activity(), downloadedGame, true, useLeanback)
            }
            return
        }
        gameLauncher.launchGameAsync(activity.activity(), game, true, useLeanback)
    }

    private fun isGamePlaceholder(game: Game): Boolean {
        val uri = Uri.parse(game.fileUri)
        return uri.scheme == "file" && uri.path?.let { File(it).length() == 0L } == true
    }

    fun onGameRestart(game: Game) {
        if (!ensureNotBusy()) {
            return
        }
        if (!ensureNotificationsPermissionAvailable()) {
            return
        }
        gameLauncher.launchGameAsync(activity.activity(), game, false, useLeanback)
    }

    fun onFavoriteToggle(
        game: Game,
        isFavorite: Boolean,
    ) {
        val lifecycleOwner = activity.activity() as? LifecycleOwner ?: return
        lifecycleOwner.lifecycleScope.launch {
            retrogradeDb.gameDao().update(game.copy(isFavorite = isFavorite))
        }
    }

    fun onCreateShortcut(game: Game) {
        val lifecycleOwner = activity.activity() as? LifecycleOwner ?: return
        lifecycleOwner.lifecycleScope.launch {
            shortcutsGenerator.pinShortcutForGame(game)
        }
    }

    fun supportShortcuts(): Boolean {
        return shortcutsGenerator.supportShortcuts()
    }

    /** True when the deletion actions below are wired (TV UI only — see the constructor). */
    fun supportsRomDeletion(): Boolean = romOnDemandManager != null

    /** True when there is an actual ROM on disk, i.e. not a 0-byte catalog placeholder. */
    fun isRomDownloaded(game: Game): Boolean = !isGamePlaceholder(game)

    /** Deletes the downloaded file only — the game stays in the catalog as a placeholder. */
    fun onDeleteRom(game: Game) {
        val manager = romOnDemandManager ?: return
        confirmDestructiveAction(
            titleId = R.string.delete_rom_confirm_title,
            message = activity.activity().getString(R.string.delete_rom_confirm_message, game.title),
        ) {
            manager.deleteRom(game)
        }
    }

    /** Deletes the file AND drops the game from the catalog (see RomOnDemandManager). */
    fun onDeleteFromCatalog(game: Game) {
        val manager = romOnDemandManager ?: return
        confirmDestructiveAction(
            titleId = R.string.delete_from_catalog_confirm_title,
            message =
                activity.activity()
                    .getString(R.string.delete_from_catalog_confirm_message, game.title),
        ) {
            manager.deleteFromCatalog(game)
        }
    }

    private fun confirmDestructiveAction(
        titleId: Int,
        message: String,
        action: suspend () -> Unit,
    ) {
        val hostActivity = activity.activity()
        // The context menu can outlive the activity on TV; showing on a dead token throws
        // BadTokenException and takes the process with it (same reason as SafeToast).
        if (hostActivity.isFinishing || hostActivity.isDestroyed) return
        val lifecycleOwner = hostActivity as? LifecycleOwner ?: return

        AlertDialog.Builder(hostActivity)
            .setTitle(titleId)
            .setMessage(message)
            .setPositiveButton(R.string.delete_confirm_action) { _, _ ->
                lifecycleOwner.lifecycleScope.launch { action() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun ensureNotificationsPermissionAvailable(): Boolean {
        if (useLeanback || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }

        val permissionResult =
            ContextCompat.checkSelfPermission(
                activity.activity(),
                Manifest.permission.POST_NOTIFICATIONS,
            )

        if (permissionResult == PackageManager.PERMISSION_GRANTED) {
            return true
        }

        activity.activity().displayToast(R.string.game_interactor_notification_permission_required)
        return false
    }

    private fun ensureNotBusy(): Boolean {
        if (activity.isBusy()) {
            activity.activity().displayToast(R.string.game_interactory_busy)
            return false
        }
        return true
    }
}
