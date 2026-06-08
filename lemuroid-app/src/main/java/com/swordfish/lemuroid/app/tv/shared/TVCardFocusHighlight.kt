package com.swordfish.lemuroid.app.tv.shared

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView

/**
 * Adds a prominent colored border around [ImageCardView] cards when they receive
 * focus.  The default Leanback focus animation (slight zoom + shadow) is too subtle
 * on low-end TV boxes, making it hard to tell which item is selected.
 */
object TVCardFocusHighlight {

    private const val BORDER_WIDTH_DP = 3

    fun setupOnCard(cardView: ImageCardView, onFocusChanged: ((Boolean) -> Unit)? = null) {
        val density = cardView.resources.displayMetrics.density
        val borderPx = (BORDER_WIDTH_DP * density).toInt()
        val colorResId = cardView.resources.getIdentifier("main_color", "color", cardView.context.packageName)
        val focusColor =
            if (colorResId != 0) ContextCompat.getColor(cardView.context, colorResId)
            else Color.parseColor("#FF9800")

        cardView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                val border = GradientDrawable().apply {
                    setStroke(borderPx, focusColor)
                    setColor(0x00000000) // transparent fill
                }
                v.foreground = border
            } else {
                v.foreground = null
            }

            onFocusChanged?.invoke(hasFocus)
        }
    }
}
