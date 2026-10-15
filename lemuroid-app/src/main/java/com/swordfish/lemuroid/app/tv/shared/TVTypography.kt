package com.swordfish.lemuroid.app.tv.shared

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.FontRes
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.app.HeadersSupportFragment
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.RowHeaderPresenter
import androidx.recyclerview.widget.RecyclerView
import com.swordfish.lemuroid.R
import timber.log.Timber

/**
 * Outfit, the redesign font, on the Leanback UI. It is applied by code because `android:fontFamily` with a
 * `res/font` resource only works from Android 8, and the TV boxes this UI exists for run 7.1.
 * If a font fails to load, the text keeps the system font.
 */
object TVTypography {
    enum class Weight(
        @FontRes val fontRes: Int,
    ) {
        REGULAR(R.font.outfit_regular),
        MEDIUM(R.font.outfit_medium),
        BOLD(R.font.outfit_bold),
    }

    private val typefaces = mutableMapOf<Weight, Typeface?>()

    @Synchronized
    private fun typeface(
        context: Context,
        weight: Weight,
    ): Typeface? {
        return typefaces.getOrPut(weight) {
            runCatching { ResourcesCompat.getFont(context.applicationContext, weight.fontRes) }
                .onFailure { Timber.w(it, "Could not load font $weight") }
                .getOrNull()
        }
    }

    fun apply(
        textView: TextView?,
        weight: Weight,
    ) {
        textView ?: return
        val typeface = typeface(textView.context, weight) ?: return
        if (textView.typeface != typeface) {
            textView.typeface = typeface
        }
    }

    fun styleCard(cardView: ImageCardView) {
        apply(cardView.findViewById(androidx.leanback.R.id.title_text), Weight.MEDIUM)
        apply(cardView.findViewById(androidx.leanback.R.id.content_text), Weight.REGULAR)
    }

    /**
     * The headers panel of [BrowseSupportFragment] builds its items with a [RowHeaderPresenter] constructor
     * that is restricted to the library, so the font goes in when each item is attached to the panel grid.
     */
    fun styleBrowseHeaders(fragment: BrowseSupportFragment) {
        fragment.childFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: FragmentManager,
                    f: Fragment,
                    v: View,
                    savedInstanceState: Bundle?,
                ) {
                    if (f !is HeadersSupportFragment) return
                    f.verticalGridView?.addOnChildAttachStateChangeListener(
                        object : RecyclerView.OnChildAttachStateChangeListener {
                            override fun onChildViewAttachedToWindow(view: View) {
                                applyToTree(view, Weight.MEDIUM)
                            }

                            override fun onChildViewDetachedFromWindow(view: View) = Unit
                        },
                    )
                }
            },
            false,
        )
    }

    private fun applyToTree(
        view: View,
        weight: Weight,
    ) {
        when (view) {
            is TextView -> apply(view, weight)
            is ViewGroup -> for (i in 0 until view.childCount) applyToTree(view.getChildAt(i), weight)
        }
    }
}

/** Header above each row ([TVListRowPresenter]) in Outfit. */
class TVRowHeaderPresenter : RowHeaderPresenter() {
    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val viewHolder = super.onCreateViewHolder(parent)
        TVTypography.apply(
            viewHolder.view.findViewById(androidx.leanback.R.id.row_header),
            TVTypography.Weight.MEDIUM,
        )
        TVTypography.apply(
            viewHolder.view.findViewById(androidx.leanback.R.id.row_header_description),
            TVTypography.Weight.REGULAR,
        )
        return viewHolder
    }
}
