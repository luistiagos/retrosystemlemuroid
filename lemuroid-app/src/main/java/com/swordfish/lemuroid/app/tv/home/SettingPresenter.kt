package com.swordfish.lemuroid.app.tv.home

import android.view.ViewGroup
import android.widget.ImageView
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.swordfish.lemuroid.app.tv.shared.TVCardFocusHighlight
import com.swordfish.lemuroid.app.tv.shared.TVTypography
import com.swordfish.lemuroid.app.tv.shared.newTVCardView

/**
 * Setting card. In the Favorites row it takes the game card size and, as the games there, shows only the icon
 * (without [showInfo]: the focus panel of the home carries the label), so the row keeps one height.
 */
class SettingPresenter(
    private val cardWidth: Int,
    private val cardHeight: Int,
    private val cardPadding: Int,
    private val showInfo: Boolean = true,
) : Presenter() {
    override fun onBindViewHolder(
        viewHolder: Presenter.ViewHolder?,
        item: Any,
    ) {
        val viewHolder = viewHolder as? ViewHolder ?: return
        val setting = item as TVSetting
        val label = viewHolder.view.context.resources.getString(setting.type.text)
        if (showInfo) {
            viewHolder.mCardView.titleText = label
        } else {
            viewHolder.mCardView.contentDescription = label
        }
        viewHolder.mCardView.setMainImageDimensions(cardWidth, cardHeight)
        viewHolder.mCardView.mainImageView.setImageResource(setting.type.icon)

        viewHolder.mCardView.mainImageView.setPadding(cardPadding, cardPadding, cardPadding, cardPadding)
        viewHolder.mCardView.setMainImageScaleType(ImageView.ScaleType.FIT_CENTER)

        viewHolder.mCardView.isEnabled = setting.enabled
    }

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val cardView = newTVCardView(parent.context, showInfo)
        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        TVTypography.styleCard(cardView)
        TVCardFocusHighlight.setupOnCard(cardView)
        return ViewHolder(cardView)
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder?) {
        val vh = viewHolder as? ViewHolder ?: return
        vh.mCardView.mainImage = null
    }

    class ViewHolder(view: ImageCardView) : Presenter.ViewHolder(view) {
        val mCardView: ImageCardView = view
    }
}
