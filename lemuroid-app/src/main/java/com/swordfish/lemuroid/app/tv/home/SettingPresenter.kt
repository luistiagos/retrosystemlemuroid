package com.swordfish.lemuroid.app.tv.home

import android.view.ViewGroup
import android.widget.ImageView
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.swordfish.lemuroid.app.tv.shared.TVCardFocusHighlight

class SettingPresenter(private val cardSize: Int, private val cardPadding: Int) : Presenter() {
    override fun onBindViewHolder(
        viewHolder: Presenter.ViewHolder?,
        item: Any,
    ) {
        val viewHolder = viewHolder as? ViewHolder ?: return
        val setting = item as TVSetting
        viewHolder.mCardView.titleText = viewHolder.view.context.resources.getString(setting.type.text)
        viewHolder.mCardView.setMainImageDimensions(cardSize, cardSize)
        viewHolder.mCardView.mainImageView.setImageResource(setting.type.icon)

        viewHolder.mCardView.mainImageView.setPadding(cardPadding, cardPadding, cardPadding, cardPadding)
        viewHolder.mCardView.setMainImageScaleType(ImageView.ScaleType.FIT_CENTER)

        viewHolder.mCardView.isEnabled = setting.enabled
    }

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val cardView = ImageCardView(parent.context)
        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
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
