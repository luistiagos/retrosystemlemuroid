package com.swordfish.lemuroid.app.tv.home

import android.graphics.Color
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.systems.MetaSystemInfo
import com.swordfish.lemuroid.app.tv.shared.TVCardFocusHighlight

class SystemPresenter(private val cardSize: Int, private val cardPadding: Int) : Presenter() {
    override fun onBindViewHolder(
        viewHolder: Presenter.ViewHolder?,
        item: Any,
    ) {
        val viewHolder = viewHolder as? ViewHolder ?: return
        val systemInfo = item as? MetaSystemInfo ?: return
        val context = viewHolder.view.context

        viewHolder.mCardView.titleText = context.resources.getString(systemInfo.metaSystem.titleResId)
        viewHolder.mCardView.contentText = context.getString(R.string.system_grid_details, systemInfo.count.toString())
        viewHolder.mCardView.setMainImageDimensions(cardSize, cardSize)
        viewHolder.mCardView.mainImageView.setImageResource(systemInfo.metaSystem.imageResId)
        viewHolder.mCardView.mainImageView.setPadding(cardPadding, cardPadding, cardPadding, cardPadding)
        viewHolder.mCardView.setMainImageScaleType(ImageView.ScaleType.FIT_CENTER)
        viewHolder.mCardView.mainImageView.setBackgroundColor(systemInfo.metaSystem.color())
    }

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val cardView = ImageCardView(parent.context)
        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        cardView.findViewById<TextView>(androidx.leanback.R.id.content_text)?.setTextColor(Color.LTGRAY)
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
