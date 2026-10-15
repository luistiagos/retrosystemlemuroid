package com.swordfish.lemuroid.app.tv.shared

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

/** Game card of the redesign: 3:4 cover ([R.dimen.tv_game_card_width] x [R.dimen.tv_game_card_height]). */
class GamePresenter(
    private val gameInteractor: GameInteractor,
) : Presenter() {
    override fun onBindViewHolder(
        viewHolder: Presenter.ViewHolder?,
        item: Any?,
    ) {
        if (item == null || viewHolder !is ViewHolder) return
        val game = item as? Game ?: return
        viewHolder.mCardView.titleText = game.title
        viewHolder.mCardView.contentText = GameUtils.getGameSubtitle(viewHolder.mCardView.context, game)
        viewHolder.updateCardViewImage(game)
        viewHolder.view.setOnCreateContextMenuListener(GameContextMenuListener(gameInteractor, game))
    }

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val cardView = ImageCardView(parent.context)
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
        return ViewHolder(cardView)
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder?) {
        val viewHolder = viewHolder as ViewHolder
        viewHolder.mCardView.mainImage = null
        viewHolder.view.setOnCreateContextMenuListener(null)
    }

    class ViewHolder(view: ImageCardView) : Presenter.ViewHolder(view) {
        val mCardView: ImageCardView = view

        fun updateCardViewImage(game: Game) {
            TVCovers.loadCover(game, mCardView.mainImageView)
        }
    }

    private companion object {
        const val TITLE_LINES = 2
    }
}
