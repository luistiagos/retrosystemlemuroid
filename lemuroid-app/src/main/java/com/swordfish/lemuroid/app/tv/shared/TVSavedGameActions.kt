package com.swordfish.lemuroid.app.tv.shared

import android.content.Context
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.installed.InstalledGameGroupUiModel
import com.swordfish.lemuroid.app.shared.GameInteractor
import com.swordfish.lemuroid.app.tv.home.TVSavedVariantsDialog
import com.swordfish.lemuroid.common.displayToast
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Shared click handling for saved game groups on Android TV:
 * - Unreachable media warns with a toast.
 * - Single saved variant plays directly.
 * - Multiple saved variants opens [TVSavedVariantsDialog] showing only the saved ones.
 *
 * Used by both the TV home row and the full "Saved games" grid screen.
 */
class TVSavedGameActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val gameInteractor: GameInteractor,
    private val savedVariants: suspend (Game) -> List<Game>,
    private val isAvailable: suspend (Game) -> Boolean,
) {
    fun onSavedGameClick(savedGame: InstalledGameGroupUiModel) {
        val game = savedGame.group.game
        if (!savedGame.isAvailable) {
            context.displayToast(R.string.installed_media_unavailable)
            return
        }
        if (savedGame.group.installedVariantsCount <= 1) {
            gameInteractor.onGamePlay(game)
            return
        }
        scope.launch {
            val variants = savedVariants(game)
            if (variants.isEmpty()) {
                gameInteractor.onGamePlay(game)
                return@launch
            }
            TVSavedVariantsDialog.show(context, game.title, variants) { variant ->
                onSavedVariantSelected(variant)
            }
        }
    }

    /** Same order as the phone's picker: a version not saved yet goes to the save dialog first. */
    fun onSavedVariantSelected(variant: Game) {
        if (!gameInteractor.isRomDownloaded(variant)) {
            gameInteractor.onGamePlay(variant)
            return
        }
        scope.launch {
            if (isAvailable(variant)) {
                gameInteractor.onGamePlay(variant)
            } else {
                context.displayToast(R.string.installed_media_unavailable)
            }
        }
    }
}
