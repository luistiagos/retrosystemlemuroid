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
import com.swordfish.lemuroid.lib.library.SystemLogoResolver

class SystemPresenter(private val cardSize: Int, private val cardPadding: Int) : Presenter() {
    override fun onBindViewHolder(
        viewHolder: Presenter.ViewHolder?,
        item: Any,
    ) {
        val holder = viewHolder as? ViewHolder ?: return
        val systemInfo = item as? MetaSystemInfo ?: return
        val context = holder.view.context

        holder.mCardView.titleText = context.resources.getString(systemInfo.metaSystem.titleResId)
        holder.mCardView.contentText = context.getString(R.string.system_grid_details, systemInfo.count.toString())
        holder.mCardView.setMainImageDimensions(cardSize, cardSize)
        holder.mCardView.mainImageView.setImageResource(
            SystemLogoResolver.resolve(systemInfo.metaSystem, hovered = holder.mCardView.hasFocus())
        )
        holder.mCardView.mainImageView.setPadding(cardPadding, cardPadding, cardPadding, cardPadding)
        holder.mCardView.setMainImageScaleType(ImageView.ScaleType.FIT_CENTER)
        holder.mCardView.mainImageView.setBackgroundColor(systemInfo.metaSystem.color())

        holder.mCardView.tag = systemInfo
    }

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val cardView = ImageCardView(parent.context)
        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        cardView.findViewById<TextView>(androidx.leanback.R.id.content_text)?.setTextColor(Color.LTGRAY)
        TVCardFocusHighlight.setupOnCard(cardView) { hasFocus ->
            val systemInfo = cardView.tag as? MetaSystemInfo ?: return@setupOnCard
            cardView.mainImageView.setImageResource(
                SystemLogoResolver.resolve(systemInfo.metaSystem, hovered = hasFocus)
            )
        }
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
