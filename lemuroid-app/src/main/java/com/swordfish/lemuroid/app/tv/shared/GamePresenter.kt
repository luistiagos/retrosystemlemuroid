package com.swordfish.lemuroid.app.tv.shared

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.GameContextMenuListener
import com.swordfish.lemuroid.app.shared.GameInteractor
import com.swordfish.lemuroid.app.utils.games.GameUtils
import com.swordfish.lemuroid.lib.library.db.entity.Game

/**
 * Game card of the redesign: 3:4 cover ([R.dimen.tv_game_card_width] x [R.dimen.tv_game_card_height]).
 * Without [showInfo] the card is only the cover: on the home the focus panel (`TVFocusPanel`) carries title and
 * system. With [savedGames] the cover gets the "saved" badge (redesign 3.5).
 */
class GamePresenter(
    private val gameInteractor: GameInteractor,
    private val showInfo: Boolean = true,
    private val savedGames: TVSavedGames? = null,
) : Presenter() {
    override fun onBindViewHolder(
        viewHolder: Presenter.ViewHolder?,
        item: Any?,
    ) {
        if (item == null || viewHolder !is ViewHolder) return
        val game = item as? Game ?: return
        if (showInfo) {
            viewHolder.mCardView.titleText = game.title
            viewHolder.mCardView.contentText = GameUtils.getGameSubtitle(viewHolder.mCardView.context, game)
        } else {
            viewHolder.mCardView.contentDescription = game.title
        }
        viewHolder.updateCardViewImage(game)
        savedGames?.bind(viewHolder, game)
        viewHolder.view.setOnCreateContextMenuListener(GameContextMenuListener(gameInteractor, game))
    }

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val cardView = newTVCardView(parent.context, showInfo)
        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        cardView.setMainImageDimensions(
            parent.resources.getDimensionPixelSize(R.dimen.tv_game_card_width),
            parent.resources.getDimensionPixelSize(R.dimen.tv_game_card_height),
        )
        cardView.setMainImageScaleType(ImageView.ScaleType.CENTER_CROP)
        // The 3:4 card is narrower than the old square: two lines keep long titles readable, and a fixed
        // height keeps the cards of a grid row aligned.
        cardView.findViewById<TextView>(androidx.leanback.R.id.title_text)?.setLines(TITLE_LINES)
        TVTypography.styleCard(cardView)
        TVCardFocusHighlight.setupOnCard(cardView)
        val imageWidth = parent.resources.getDimensionPixelSize(R.dimen.tv_game_card_width)
        return ViewHolder(cardView, imageWidth)
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder?) {
        val viewHolder = viewHolder as ViewHolder
        savedGames?.unbind(viewHolder)
        viewHolder.mCardView.mainImage = null
        viewHolder.view.setOnCreateContextMenuListener(null)
    }

    class ViewHolder(view: ImageCardView, imageWidth: Int) : Presenter.ViewHolder(view) {
        val mCardView: ImageCardView = view
        private val savedBadge = TVSavedBadge(view.mainImageView, imageWidth)

        internal fun setSaved(saved: Boolean) = savedBadge.setShown(saved)

        fun updateCardViewImage(game: Game) {
            TVCovers.loadCover(game, mCardView.mainImageView)
        }
    }

    private companion object {
        const val TITLE_LINES = 2
    }
}

/**
 * Card of the TV rows and grids. Without [showInfo] it is only the image: `ImageCardView` reads its type from the
 * `imageCardViewStyle` of the theme, so an `ImageOnly` overlay drops the info area (title and subtitle). A card
 * without text needs a `contentDescription` for TalkBack.
 */
internal fun newTVCardView(
    context: Context,
    showInfo: Boolean,
): ImageCardView {
    if (showInfo) return ImageCardView(context)
    return ImageCardView(ContextThemeWrapper(context, R.style.LemuroidLeanbackTheme_ImageOnlyCards))
}
