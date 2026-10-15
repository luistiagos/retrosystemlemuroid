package com.swordfish.lemuroid.app.tv.home

import android.view.View
import android.view.ViewGroup
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.systems.MetaSystemInfo
import com.swordfish.lemuroid.app.tv.shared.TVCardFocusHighlight
import com.swordfish.lemuroid.app.tv.shared.TVCovers
import com.swordfish.lemuroid.app.tv.shared.TVTypography
import com.swordfish.lemuroid.app.tv.shared.newTVCardView
import com.swordfish.lemuroid.lib.library.SystemLogoResolver

/**
 * System block of the redesign: console art over the `SystemPalette` colour, 208x144. Without [showInfo] it is only
 * the art: on the home the focus panel ([TVFocusPanel]) carries name and game count.
 */
class SystemPresenter(
    private val showInfo: Boolean = true,
) : Presenter() {
    override fun onBindViewHolder(
        viewHolder: Presenter.ViewHolder?,
        item: Any,
    ) {
        val holder = viewHolder as? ViewHolder ?: return
        val systemInfo = item as? MetaSystemInfo ?: return
        val context = holder.view.context

        val name = context.resources.getString(systemInfo.metaSystem.titleResId)
        if (showInfo) {
            holder.mCardView.titleText = name
            holder.mCardView.contentText = context.getString(R.string.system_grid_details, systemInfo.count.toString())
        } else {
            holder.mCardView.contentDescription = name
        }
        holder.mCardView.tag = systemInfo
        holder.showArt(systemInfo, hovered = holder.mCardView.hasFocus())
    }

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val cardView = newTVCardView(parent.context, showInfo)
        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        cardView.setMainImageDimensions(
            parent.resources.getDimensionPixelSize(R.dimen.tv_system_card_width),
            parent.resources.getDimensionPixelSize(R.dimen.tv_system_card_height),
        )
        TVTypography.styleCard(cardView)
        val holder = ViewHolder(cardView)
        TVCardFocusHighlight.setupOnCard(cardView) { hasFocus ->
            val systemInfo = cardView.tag as? MetaSystemInfo ?: return@setupOnCard
            holder.showArt(systemInfo, hovered = hasFocus)
        }
        return holder
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder?) {
        val vh = viewHolder as? ViewHolder ?: return
        vh.mCardView.mainImage = null
        vh.mCardView.tag = null
    }

    class ViewHolder(view: ImageCardView) : Presenter.ViewHolder(view) {
        val mCardView: ImageCardView = view

        fun showArt(
            systemInfo: MetaSystemInfo,
            hovered: Boolean,
        ) {
            val imageView = mCardView.mainImageView
            // ImageCardView leaves the image INVISIBLE after `mainImage = null` (unbind).
            imageView.visibility = View.VISIBLE
            imageView.alpha = 1f
            imageView.setImageDrawable(
                TVCovers.systemCard(
                    mCardView.context,
                    systemInfo.metaSystem,
                    SystemLogoResolver.resolve(systemInfo.metaSystem, hovered),
                ),
            )
        }
    }
}
