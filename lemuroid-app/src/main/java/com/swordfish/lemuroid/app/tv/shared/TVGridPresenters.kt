package com.swordfish.lemuroid.app.tv.shared

import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.RowPresenter
import androidx.leanback.widget.VerticalGridPresenter

/*
 * Leanback grids fed by live data crash with
 * `IndexOutOfBoundsException: Invalid item position -1(-1)` when the adapter reports structural
 * changes for items placed *before* the first visible one.
 *
 * GridLayoutManager translates a grid index into an adapter position as
 * `index - mPositionDeltaInPreLayout`, and that delta is only non zero during RecyclerView's
 * pre-layout pass, where it is exactly the number of items removed above the viewport. The
 * prepend loops in StaggeredGridDefault stop at `itemIndex < 0` instead of stopping at
 * `mProvider.getMinIndex()` (which is that delta), so prepending down to grid index 0 while the
 * delta is 1 asks the Recycler for adapter position -1 and throws.
 *
 * RecyclerView only runs the pre-layout pass when an ItemAnimator is installed
 * (dispatchLayoutStep1: mRunPredictiveAnimations requires mRunSimpleAnimations, which requires
 * `mItemAnimator != null`), so dropping the animator removes the faulty path entirely. Our TV
 * grids are backed by Room PagingSources that reorder and remove rows on their own -- a finished
 * download jumps to the top of the system grid, a game leaves the favorites list, a new query
 * replaces the search results -- so they are exposed to this on every database write, and item
 * animations buy us nothing there. Focus zoom is a separate mechanism (FocusHighlightHelper) and
 * is not affected.
 *
 * See documentacao/bugs/done/2026-09-02-tv-leanback-verticalgrid-posicao-invalida.md.
 */

/** [VerticalGridPresenter] whose grid never runs predictive item animations. */
class TVVerticalGridPresenter : VerticalGridPresenter() {
    override fun initializeGridViewHolder(vh: VerticalGridPresenter.ViewHolder) {
        super.initializeGridViewHolder(vh)
        vh.gridView.setAnimateChildLayout(false)
    }
}

/** [ListRowPresenter] whose rows never run predictive item animations. */
class TVListRowPresenter : ListRowPresenter() {
    override fun initializeRowViewHolder(holder: RowPresenter.ViewHolder) {
        super.initializeRowViewHolder(holder)
        (holder as ListRowPresenter.ViewHolder).gridView.setAnimateChildLayout(false)
    }
}
