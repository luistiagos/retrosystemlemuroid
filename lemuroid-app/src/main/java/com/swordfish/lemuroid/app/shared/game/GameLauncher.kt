package com.swordfish.lemuroid.app.shared.game

import android.app.Activity
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.app.shared.main.GameLaunchTaskHandler
import com.swordfish.lemuroid.lib.core.CoresSelection
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class GameLauncher(
    private val coresSelection: CoresSelection,
    private val gameLaunchTaskHandler: GameLaunchTaskHandler,
) {
    fun launchGameAsync(
        activity: Activity,
        game: Game,
        loadSave: Boolean,
        leanback: Boolean,
    ) {
        val lifecycleOwner = activity as? LifecycleOwner ?: return
        lifecycleOwner.lifecycleScope.launch {
            val available = withContext(Dispatchers.IO) { isGameFileAvailable(activity, game) }
            if (!available) {
                showRomNotFoundDialog(activity, game)
                return@launch
            }
            val system = GameSystem.findByIdOrNull(game.systemId) ?: return@launch
            val coreConfig = coresSelection.getCoreConfigForSystem(system)
            withContext(Dispatchers.IO) { gameLaunchTaskHandler.handleGameStart(activity.applicationContext) }
            BaseGameActivity.launchGame(activity, coreConfig, game, loadSave, leanback)
        }
    }

    private fun isGameFileAvailable(activity: Activity, game: Game): Boolean {
        val uri = Uri.parse(game.fileUri)
        return when (uri.scheme) {
            "file" -> File(uri.path ?: return false).exists()
            "content" -> {
                runCatching {
                    activity.contentResolver.openInputStream(uri)?.use { true } ?: false
                }.getOrDefault(false)
            }
            else -> File(game.fileUri).exists()
        }
    }

    private fun showRomNotFoundDialog(activity: Activity, game: Game) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.game_rom_not_found_title))
            .setMessage(activity.getString(R.string.game_rom_not_found_message, game.fileName))
            .setPositiveButton(activity.getString(R.string.game_rom_not_found_rescan)) { _, _ ->
                LibraryIndexScheduler.scheduleLibrarySync(activity.applicationContext)
            }
            .setNegativeButton(activity.getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
