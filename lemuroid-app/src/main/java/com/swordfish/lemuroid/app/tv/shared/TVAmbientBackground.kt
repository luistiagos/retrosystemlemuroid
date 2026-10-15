package com.swordfish.lemuroid.app.tv.shared

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import androidx.leanback.app.BackgroundManager
import androidx.lifecycle.lifecycleScope
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.SystemPalette
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.VisualQuality
import com.swordfish.lemuroid.app.shared.systems.MetaSystemInfo
import com.swordfish.lemuroid.app.tv.home.TVSetting
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.MetaSystemID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.library.metaSystemID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.min

/**
 * Ambient background of the TV redesign (delivery 3.3): the art of the focused item behind the rows,
 * painted by the activity's [BackgroundManager].
 *
 * The background is composed into a [BACKDROP_WIDTH]x[BACKDROP_HEIGHT] bitmap (cover cropped to the
 * screen + gradient) that the [BitmapDrawable] stretches with bilinear filtering: the "blur" of the
 * design, without `RenderEffect` (Android 12+). It has to be a [BitmapDrawable]: a recreated
 * activity gets the last background back through `getConstantState().newDrawable()` (the
 * `BackgroundContinuityService` of [BackgroundManager.attach]), which a custom drawable does not have.
 *
 * In [VisualQuality.LITE] there is no image at all, only a gradient in the dark tone of the system.
 *
 * One instance per fragment view, created in `onViewCreated`.
 */
class TVAmbientBackground private constructor(
    private val fragment: Fragment,
    private val manager: BackgroundManager,
) {
    private val context: Context = fragment.requireContext()
    private val quality = VisualQuality.of(context)
    private var pending: Job? = null
    private var current: Target? = null

    /** Selection listener entry point. `null` (paging placeholder) keeps the current background. */
    fun onItemSelected(item: Any?) {
        when (item) {
            is Game -> show(targetOf(item))
            is MetaSystemInfo -> show(targetOf(item.metaSystem))
            is TVSetting -> show(Target.NEUTRAL)
        }
    }

    /** Console art of [metaSystem]: the catalog before any card is focused. */
    fun showSystem(metaSystem: MetaSystemID) = show(targetOf(metaSystem))

    fun showNeutral() = show(Target.NEUTRAL)

    private fun show(target: Target) {
        pending?.cancel()
        if (target == current) return

        pending =
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                // Only after the focus rests on the item: scrolling a row must not decode every cover.
                delay(SELECTION_DELAY_MS)
                val bitmap = render(target)
                manager.setDrawable(BitmapDrawable(context.resources, bitmap))
                current = target
            }
    }

    private suspend fun render(target: Target): Bitmap {
        val background = ContextCompat.getColor(context, R.color.leanback_background)
        val bitmap = Bitmap.createBitmap(BACKDROP_WIDTH, BACKDROP_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        val bounds = Rect(0, 0, BACKDROP_WIDTH, BACKDROP_HEIGHT)

        val drewArt = quality == VisualQuality.FULL && drawArt(canvas, bounds, target)
        if (!drewArt && target.color != null) {
            drawGradient(canvas, bounds, darkTone(target.color), background)
        }
        if (target != Target.NEUTRAL) {
            drawProtection(canvas, bounds, background)
        }
        return bitmap
    }

    /** Cover (cropped to the screen) or console art, at [ART_ALPHA]. False when there is nothing to draw. */
    private suspend fun drawArt(
        canvas: Canvas,
        bounds: Rect,
        target: Target,
    ): Boolean {
        val cover = target.coverUrl?.let { loadCover(it) }
        if (cover != null) {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = ART_ALPHA }
            canvas.drawBitmap(cover, centerCrop(cover, bounds), bounds, paint)
            return true
        }

        val artResId = target.artResId ?: return false
        val art = TVCovers.systemArt(context, artResId) ?: return false
        val drawable = SystemArtDrawable(target.color ?: 0, art, SystemArtDrawable.Placement.FILL)
        drawable.bounds = bounds
        drawable.alpha = ART_ALPHA
        drawable.draw(canvas)
        return true
    }

    private suspend fun loadCover(url: String): Bitmap? {
        val request =
            ImageRequest.Builder(context)
                .data(url)
                .size(COVER_DECODE_PX)
                // A hardware bitmap cannot be drawn on the software canvas of the backdrop.
                .allowHardware(false)
                .build()
        // `execute` reports failures as ErrorResult; only cancellation is thrown, and it must propagate.
        return when (val result = context.imageLoader.execute(request)) {
            is SuccessResult -> result.drawable.toBitmap()
            is ErrorResult -> {
                Timber.w(result.throwable, "Ambient background: cover not loaded")
                null
            }
        }
    }

    private fun centerCrop(
        image: Bitmap,
        bounds: Rect,
    ): Rect {
        val dstAspect = bounds.width().toFloat() / bounds.height()
        val width = min(image.width, (image.height * dstAspect).toInt()).coerceAtLeast(1)
        val height = min(image.height, (image.width / dstAspect).toInt()).coerceAtLeast(1)
        val left = (image.width - width) / 2
        val top = (image.height - height) / 2
        return Rect(left, top, left + width, top + height)
    }

    private fun drawGradient(
        canvas: Canvas,
        bounds: Rect,
        @ColorInt top: Int,
        @ColorInt bottom: Int,
    ) {
        val paint = Paint()
        paint.shader =
            LinearGradient(0f, 0f, 0f, bounds.height().toFloat(), top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
    }

    /** Fades the art into the page, lighter than on mobile: on the TV the rows cover most of the screen. */
    private fun drawProtection(
        canvas: Canvas,
        bounds: Rect,
        @ColorInt background: Int,
    ) {
        val paint = Paint()
        paint.shader =
            LinearGradient(
                0f,
                0f,
                0f,
                bounds.height().toFloat(),
                intArrayOf(
                    ColorUtils.setAlphaComponent(background, PROTECTION_TOP),
                    ColorUtils.setAlphaComponent(background, PROTECTION_MIDDLE),
                    ColorUtils.setAlphaComponent(background, PROTECTION_BOTTOM),
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        canvas.drawRect(bounds, paint)
    }

    private fun targetOf(game: Game): Target {
        val metaSystem = GameSystem.findByIdOrNull(game.systemId)?.metaSystemID()
        return Target(
            coverUrl = game.coverFrontUrl?.takeIf { it.isNotBlank() },
            artResId = metaSystem?.imageResId,
            color = SystemPalette.colorForSystemId(game.systemId).toArgb(),
        )
    }

    private fun targetOf(metaSystem: MetaSystemID): Target =
        Target(
            coverUrl = null,
            artResId = metaSystem.imageResId,
            color = SystemPalette.color(metaSystem).toArgb(),
        )

    private fun darkTone(
        @ColorInt color: Int,
    ): Int = SystemPalette.darkTone(androidx.compose.ui.graphics.Color(color)).toArgb()

    /** What the background shows; equal targets do not repaint. */
    private data class Target(
        val coverUrl: String?,
        @DrawableRes val artResId: Int?,
        @ColorInt val color: Int?,
    ) {
        companion object {
            val NEUTRAL = Target(coverUrl = null, artResId = null, color = null)
        }
    }

    companion object {
        private const val SELECTION_DELAY_MS = 300L

        // Small on purpose: the stretch is the blur (the cover already comes at 32 px).
        private const val BACKDROP_WIDTH = 48
        private const val BACKDROP_HEIGHT = 27
        private const val COVER_DECODE_PX = 32
        private const val ART_ALPHA = 153 // 0.6, as the mobile AmbientBackdrop
        private const val PROTECTION_TOP = 77 // 0.3
        private const val PROTECTION_MIDDLE = 153 // 0.6
        private const val PROTECTION_BOTTOM = 230 // 0.9

        /** Attaches the activity's [BackgroundManager] (once) and returns the helper of [fragment]. */
        fun attach(fragment: Fragment): TVAmbientBackground {
            val activity = fragment.requireActivity()
            val manager = BackgroundManager.getInstance(activity)
            if (!manager.isAttached) {
                // With the default auto release, onStop drops the drawable and onStart does not bring it back:
                // the background was gone after the game. It is a ~5 KB bitmap, there is nothing to release.
                manager.isAutoReleaseOnStop = false
                manager.attach(activity.window)
            }
            return TVAmbientBackground(fragment, manager)
        }
    }
}
