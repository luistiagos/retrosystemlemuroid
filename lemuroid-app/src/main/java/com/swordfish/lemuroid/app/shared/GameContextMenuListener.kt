package com.swordfish.lemuroid.app.shared

import android.view.ContextMenu
import android.view.View
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.lib.library.db.entity.Game

class GameContextMenuListener(
    private val gameInteractor: GameInteractor,
    private val game: Game,
) : View.OnCreateContextMenuListener {
    override fun onCreateContextMenu(
        menu: ContextMenu,
        v: View,
        menuInfo: ContextMenu.ContextMenuInfo?,
    ) {
        menu.add(R.string.game_context_menu_resume).setOnMenuItemClickListener {
            gameInteractor.onGamePlay(game)
            true
        }

        menu.add(R.string.game_context_menu_restart).setOnMenuItemClickListener {
            gameInteractor.onGameRestart(game)
            true
        }

        if (game.isFavorite) {
            menu.add(R.string.game_context_menu_remove_from_favorites).setOnMenuItemClickListener {
                gameInteractor.onFavoriteToggle(game, false)
                true
            }
        } else {
            menu.add(R.string.game_context_menu_add_to_favorites).setOnMenuItemClickListener {
                gameInteractor.onFavoriteToggle(game, true)
                true
            }
        }

        if (gameInteractor.supportShortcuts()) {
            menu.add(R.string.game_context_menu_create_shortcut).setOnMenuItemClickListener {
                gameInteractor.onCreateShortcut(game)
                true
            }
        }

        if (gameInteractor.supportsRomDeletion()) {
            // Frees the disk space but keeps the catalog entry — nothing to delete when the
            // game is still a placeholder, hence the guard.
            if (gameInteractor.isRomDownloaded(game)) {
                menu.add(R.string.game_context_menu_delete_rom).setOnMenuItemClickListener {
                    gameInteractor.onDeleteRom(game)
                    true
                }
            }

            menu.add(R.string.game_context_menu_delete_from_catalog).setOnMenuItemClickListener {
                gameInteractor.onDeleteFromCatalog(game)
                true
            }
        }
    }
}
