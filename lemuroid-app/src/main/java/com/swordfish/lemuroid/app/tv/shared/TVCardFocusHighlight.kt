package com.swordfish.lemuroid.app.tv.shared

import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import com.swordfish.lemuroid.R

/**
 * Adds a prominent colored border around [ImageCardView] cards when they receive
 * focus.  The default Leanback focus animation (slight zoom + shadow) is too subtle
 * on low-end TV boxes, making it hard to tell which item is selected.
 */
object TVCardFocusHighlight {

    private const val BORDER_WIDTH_DP = 3

    fun setupOnCard(cardView: ImageCardView) {
        val density = cardView.resources.displayMetrics.density
        val borderPx = (BORDER_WIDTH_DP * density).toInt()
        val focusColor = ContextCompat.getColor(cardView.context, R.color.main_color)

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
        }
    }
}
