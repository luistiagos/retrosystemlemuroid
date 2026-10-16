package com.swordfish.lemuroid.app.tv.shared

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.common.coroutines.launchOnState
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.flow.map
import java.util.WeakHashMap

/**
 * "Saved" badge of the TV game cards (redesign 3.5): the ✓ of the mobile `CoverSaveState.Saved`, top-end corner of
 * the cover.
 *
 * One instance per fragment view, shared by every [GamePresenter] of that fragment (the home recreates its
 * presenters). It keeps the saved keys (`Game.downloadKey`) and the bound cards: when a game is saved or its ROM is
 * deleted only the bound cards are repainted, the paged grids are not rebuilt. Main thread only.
 *
 * Only "saved": the TV does not use the save queue, saving is the modal `TVRomDownloadDialog`. The badge is static,
 * so `VisualQuality.LITE` draws the same thing.
 */
class TVSavedGames private constructor() {
    private var keys: Set<String> = emptySet()
    private val boundCards = WeakHashMap<GamePresenter.ViewHolder, Game>()

    internal fun bind(
        holder: GamePresenter.ViewHolder,
        game: Game,
    ) {
        boundCards[holder] = game
        holder.setSaved(game.downloadKey in keys)
    }

    internal fun unbind(holder: GamePresenter.ViewHolder) {
        boundCards.remove(holder)
        holder.setSaved(false)
    }

    private fun update(newKeys: Set<String>) {
        if (newKeys == keys) return
        keys = newKeys
        boundCards.forEach { (holder, game) -> holder.setSaved(game.downloadKey in newKeys) }
    }

    companion object {
        /** Call in `onViewCreated`, before creating the presenters: it collects on the view lifecycle. */
        fun attach(
            fragment: Fragment,
            retrogradeDb: RetrogradeDatabase,
        ): TVSavedGames {
            val savedGames = TVSavedGames()
            fragment.launchOnState(Lifecycle.State.STARTED) {
                retrogradeDb.downloadedRomDao()
                    .observeAllDownloadedKeys()
                    .map { it.toHashSet() }
                    .collect { savedGames.update(it) }
            }
            return savedGames
        }
    }
}

/**
 * Shows or hides the saved badge over [imageView], in its `ViewOverlay`: the card `foreground` is the focus border
 * ([TVCardFocusHighlight]) and the info area, where `ImageCardView` puts its own badge, does not exist on the home
 * cards.
 */
internal class TVSavedBadge(private val imageView: ImageView, imageWidth: Int) {
    private val context: Context = imageView.context
    private var shown = false

    private val drawable: Drawable by lazy {
        val density = context.resources.displayMetrics.density
        val size = (BADGE_SIZE_DP * density).toInt()
        val margin = (BADGE_MARGIN_DP * density).toInt()
        SavedBadgeDrawable(
            ContextCompat.getColor(context, R.color.leanback_accent),
            ContextCompat.getColor(context, R.color.leanback_on_accent),
            CHECK_STROKE_DP * density,
        ).apply {
            setBounds(imageWidth - margin - size, margin, imageWidth - margin, margin + size)
        }
    }

    fun setShown(saved: Boolean) {
        if (saved == shown) return
        shown = saved
        if (saved) imageView.overlay.add(drawable) else imageView.overlay.remove(drawable)
    }

    private companion object {
        const val BADGE_SIZE_DP = 24
        const val BADGE_MARGIN_DP = 6
        const val CHECK_STROKE_DP = 2.2f
    }
}

/** Filled circle with a ✓, drawn by path (no vector resource to inflate on Android 5). */
private class SavedBadgeDrawable(
    @ColorInt background: Int,
    @ColorInt tint: Int,
    strokeWidth: Float,
) : Drawable() {
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
    private val checkPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private val check = Path()

    override fun onBoundsChange(bounds: Rect) {
        val size = bounds.width().toFloat()
        val left = bounds.left.toFloat()
        val top = bounds.top.toFloat()
        check.reset()
        check.moveTo(left + size * 0.28f, top + size * 0.52f)
        check.lineTo(left + size * 0.44f, top + size * 0.67f)
        check.lineTo(left + size * 0.72f, top + size * 0.36f)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), bounds.width() / 2f, circlePaint)
        canvas.drawPath(check, checkPaint)
    }

    override fun setAlpha(alpha: Int) {
        circlePaint.alpha = alpha
        checkPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        circlePaint.colorFilter = colorFilter
        checkPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
