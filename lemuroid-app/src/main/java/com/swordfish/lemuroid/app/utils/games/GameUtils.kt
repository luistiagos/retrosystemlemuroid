package com.swordfish.lemuroid.app.utils.games

import android.content.Context
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.db.entity.Game

class GameUtils {
    companion object {
        fun getGameSubtitle(
            context: Context,
            game: Game,
        ): String {
            val systemName = getSystemNameForGame(context, game)
            val developerName =
                if (game.developer?.isNotBlank() == true) {
                    "- ${game.developer}"
                } else {
                    ""
                }
            return "$systemName $developerName"
        }

        private fun getSystemNameForGame(
            context: Context,
            game: Game,
        ): String {
            val systemTitleResource = GameSystem.findByIdOrNull(game.systemId)?.shortTitleResId
                ?: return game.systemId
            return context.getString(systemTitleResource)
        }
    }
}
