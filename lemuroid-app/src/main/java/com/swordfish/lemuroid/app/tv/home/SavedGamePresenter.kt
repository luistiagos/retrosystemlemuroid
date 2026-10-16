package com.swordfish.lemuroid.app.tv.home

import android.content.Context
import android.view.ViewGroup
import android.widget.ImageView
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.installed.InstalledGameGroupUiModel
import com.swordfish.lemuroid.app.mobile.feature.installed.SAVED_GAME_UNAVAILABLE_ALPHA
import com.swordfish.lemuroid.app.shared.GameContextMenuListener
import com.swordfish.lemuroid.app.shared.GameInteractor
import com.swordfish.lemuroid.app.tv.shared.TVCardFocusHighlight
import com.swordfish.lemuroid.app.tv.shared.TVCovers
import com.swordfish.lemuroid.app.tv.shared.TVTypography
import com.swordfish.lemuroid.app.tv.shared.newTVCardView

/**
 * Card of the "Saved games" row of the TV home (redesign 3.6): the cover of the group representative, as the other
 * home cards (image only; the focus panel carries title, system, versions and media state).
 *
 * The image-only card has no room for the badges of the phone, so unreachable media dims the cover, as there
 * ([SAVED_GAME_UNAVAILABLE_ALPHA]).
 */
class SavedGamePresenter(
    private val gameInteractor: GameInteractor,
) : Presenter() {
    override fun onBindViewHolder(
        viewHolder: ViewHolder?,
        item: Any?,
    ) {
        val cardView = viewHolder?.view as? ImageCardView ?: return
        val savedGame = item as? InstalledGameGroupUiModel ?: return
        val game = savedGame.group.game

        cardView.contentDescription = describe(cardView, savedGame)
        TVCovers.loadCover(game, cardView.mainImageView)
        cardView.mainImageView.alpha = if (savedGame.isAvailable) 1f else SAVED_GAME_UNAVAILABLE_ALPHA
        cardView.setOnCreateContextMenuListener(GameContextMenuListener(gameInteractor, game))
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = newTVCardView(parent.context, showInfo = false)
        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        cardView.setMainImageDimensions(
            parent.resources.getDimensionPixelSize(R.dimen.tv_game_card_width),
            parent.resources.getDimensionPixelSize(R.dimen.tv_game_card_height),
        )
        cardView.setMainImageScaleType(ImageView.ScaleType.CENTER_CROP)
        TVTypography.styleCard(cardView)
        TVCardFocusHighlight.setupOnCard(cardView)
        return ViewHolder(cardView)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder?) {
        val cardView = viewHolder?.view as? ImageCardView ?: return
        cardView.mainImage = null
        cardView.mainImageView.alpha = 1f
        cardView.setOnCreateContextMenuListener(null)
    }

    private fun describe(
        cardView: ImageCardView,
        savedGame: InstalledGameGroupUiModel,
    ): String {
        val context = cardView.context
        return listOfNotNull(
            savedGame.group.game.title,
            savedGameDetail(context, savedGame),
        ).joinToString(", ")
    }
}

/**
 * State of a saved group in words: unreachable media first (the tap only warns), then the number of saved versions
 * when more than one. `null` when there is nothing to tell. Shared by the card description and the focus panel.
 */
internal fun savedGameDetail(
    context: Context,
    savedGame: InstalledGameGroupUiModel,
): String? {
    return when {
        !savedGame.isAvailable -> context.getString(R.string.installed_media_unavailable)
        savedGame.group.installedVariantsCount > 1 ->
            context.getString(R.string.installed_versions_count, savedGame.group.installedVariantsCount)
        else -> null
    }
}
