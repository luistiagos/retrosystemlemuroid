package com.swordfish.lemuroid.app.tv.home

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.compose.ui.graphics.toArgb
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.app.RowsSupportFragment
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.SystemPalette
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.VisualQuality
import com.swordfish.lemuroid.app.shared.systems.MetaSystemInfo
import com.swordfish.lemuroid.app.tv.shared.TVTypography
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.db.entity.Game

/**
 * Focus panel of the TV home (redesign delivery 3.4): title and system of the focused card, in the band above the
 * rows, over the ambient background. The home cards show only the image, as on the landscape home of the phone.
 *
 * In [VisualQuality.FULL] a new item fades in; in [VisualQuality.LITE] it is swapped without animation.
 */
class TVFocusPanel private constructor(
    private val view: View,
) {
    private val context: Context = view.context
    private val title: TextView = view.findViewById(R.id.tv_focus_panel_title)
    private val chip: TextView = view.findViewById(R.id.tv_focus_panel_chip)
    private val chipBackground =
        GradientDrawable().apply {
            // Larger than half the chip height: a pill, as the SystemChip of the phone.
            cornerRadius = context.resources.displayMetrics.density * CHIP_RADIUS_DP
        }
    private val animate = VisualQuality.of(context) == VisualQuality.FULL
    private var current: Any? = null
    private var pending: Content? = null
    private val applyPending = Runnable { pending?.let { apply(it) } }

    init {
        chip.background = chipBackground
        TVTypography.apply(title, TVTypography.Weight.BOLD)
        TVTypography.apply(chip, TVTypography.Weight.BOLD)
    }

    /** Selection listener entry point. `null` and unknown items keep the current content. */
    fun onItemSelected(item: Any?) {
        if (item == null || item == current) return
        val content = contentOf(item) ?: return
        current = item

        // Coming back from another screen, Leanback dispatches the selection from inside the layout pass of the rows:
        // the requestLayout of setText made there was lost, and the panel stayed measured with empty texts.
        pending = content
        view.removeCallbacks(applyPending)
        view.post(applyPending)
    }

    private fun apply(content: Content) {
        title.text = content.title
        if (content.chip == null) {
            chip.visibility = View.GONE
        } else {
            chip.text = content.chip
            chipBackground.setColor(content.chipColor)
            chip.visibility = View.VISIBLE
        }

        if (animate) {
            view.animate().cancel()
            view.alpha = FADE_FROM_ALPHA
            view.animate().alpha(1f).setDuration(FADE_DURATION_MS).start()
        }
    }

    /** Hidden while the headers panel is open: it would sit over it. */
    fun setShown(shown: Boolean) {
        view.animate().cancel()
        view.alpha = 1f
        view.visibility = if (shown) View.VISIBLE else View.INVISIBLE
    }

    private fun contentOf(item: Any): Content? {
        return when (item) {
            is Game -> {
                val systemName =
                    GameSystem.findByIdOrNull(item.systemId)?.shortTitleResId?.let { context.getString(it) }
                        ?: item.systemId
                Content(item.title, systemName, SystemPalette.colorForSystemId(item.systemId).toArgb())
            }
            is MetaSystemInfo ->
                Content(
                    item.getName(context),
                    context.getString(R.string.system_grid_details, item.count.toString()),
                    SystemPalette.color(item.metaSystem).toArgb(),
                )
            is TVSetting -> Content(context.getString(item.type.text), chip = null, chipColor = 0)
            else -> null
        }
    }

    private data class Content(
        val title: String,
        val chip: String?,
        @ColorInt val chipColor: Int,
    )

    companion object {
        private const val CHIP_RADIUS_DP = 999f
        private const val FADE_FROM_ALPHA = 0.3f
        private const val FADE_DURATION_MS = 150L

        /** Inflates the panel over [root], the view returned by `BrowseSupportFragment.onCreateView`. */
        fun attach(root: ViewGroup): TVFocusPanel {
            val view = LayoutInflater.from(root.context).inflate(R.layout.tv_focus_panel, root, false)
            root.addView(view)
            return TVFocusPanel(view)
        }

        /**
         * The rows above the selected one stay drawn above it, right where the panel is: their grid is cut at
         * [R.dimen.tv_home_rows_clip_top], just above the header of the selected row. Call once, in `onCreate`:
         * the rows fragment and its view are recreated with the view of [fragment].
         */
        fun clipRowsAboveSelection(fragment: BrowseSupportFragment) {
            val clipTop = fragment.resources.getDimensionPixelSize(R.dimen.tv_home_rows_clip_top)
            fragment.childFragmentManager.registerFragmentLifecycleCallbacks(
                object : FragmentManager.FragmentLifecycleCallbacks() {
                    override fun onFragmentViewCreated(
                        fm: FragmentManager,
                        f: Fragment,
                        v: View,
                        savedInstanceState: Bundle?,
                    ) {
                        if (f !is RowsSupportFragment) return
                        val grid = f.verticalGridView ?: return
                        grid.addOnLayoutChangeListener { gridView, left, top, right, bottom, _, _, _, _ ->
                            val clip = Rect(0, clipTop, right - left, bottom - top)
                            if (gridView.clipBounds != clip) gridView.clipBounds = clip
                        }
                    }
                },
                false,
            )
        }
    }
}
