package com.swordfish.lemuroid.app.tv.shared

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import coil.dispose
import coil.imageLoader
import coil.load
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.SystemArtCache
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.SystemPalette
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.MetaSystemID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.library.metaSystemID
import kotlin.math.abs
import kotlin.math.min

/**
 * Covers and system art of the redesign on the Leanback cards. Same drawing as the mobile
 * `GeneratedCover` / `LemuroidSystemImage`, as plain [Drawable]s for the `View` presenters.
 */
object TVCovers {
    /**
     * Loads the cover of [game] into [imageView]. Without a URL, or when the download fails, shows the
     * generated cover (dark tone of the system colour + console art); while loading, the dark tone.
     */
    fun loadCover(
        game: Game,
        imageView: ImageView,
    ) {
        // ImageCardView leaves the image INVISIBLE after `mainImage = null` (unbind) and when it is
        // created without a drawable; Coil sets the drawable straight on the ImageView.
        imageView.visibility = View.VISIBLE
        imageView.alpha = 1f

        val context = imageView.context
        if (game.coverFrontUrl.isNullOrBlank()) {
            imageView.dispose()
            imageView.setImageDrawable(generatedCover(context, game))
            return
        }

        imageView.load(game.coverFrontUrl, context.imageLoader) {
            placeholder(ColorDrawable(darkTone(game)))
            listener(onError = { _, _ -> imageView.setImageDrawable(generatedCover(context, game)) })
        }
    }

    fun generatedCover(
        context: Context,
        game: Game,
    ): Drawable {
        val metaSystem = metaSystemOf(game)
        val art = metaSystem?.let { systemArt(context, it.imageResId) }
        return SystemArtDrawable(darkTone(game), art, SystemArtDrawable.Placement.CENTERED_BAND)
    }

    /** Console art over the system colour, filling the card ([SystemArtDrawable.Placement.FILL]). */
    fun systemCard(
        context: Context,
        metaSystem: MetaSystemID,
        @DrawableRes artResId: Int,
    ): Drawable {
        val color = SystemPalette.color(metaSystem).toArgb()
        return SystemArtDrawable(color, systemArt(context, artResId), SystemArtDrawable.Placement.FILL)
    }

    private fun metaSystemOf(game: Game): MetaSystemID? = GameSystem.findByIdOrNull(game.systemId)?.metaSystemID()

    private fun darkTone(game: Game): Int =
        SystemPalette.darkTone(SystemPalette.colorForSystemId(game.systemId)).toArgb()

    /** Decoded once per art and shared with the mobile UI; a PNG of ~277x192, a few ms on a TV box. */
    internal fun systemArt(
        context: Context,
        @DrawableRes resId: Int,
    ): Bitmap? = SystemArtCache.load(context, resId)?.asAndroidBitmap()
}

/**
 * Background colour plus the console art (`game_system_*`) blended with [PorterDuff.Mode.SCREEN], so
 * its black background takes the colour, without the white frame baked into the PNG.
 */
class SystemArtDrawable(
    private val backgroundColor: Int,
    private val art: Bitmap?,
    private val placement: Placement,
) : Drawable() {
    enum class Placement {
        /** The art fills the bounds (system card). */
        FILL,

        /** The art is 90% of the width, vertically centred (generated cover; the title is under the card). */
        CENTERED_BAND,
    }

    private val backgroundPaint = Paint().apply { color = backgroundColor }
    private val artPaint =
        Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
    private val src = Rect()
    private val dst = RectF()

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        canvas.drawRect(bounds, backgroundPaint)
        val art = art ?: return
        if (art.isRecycled) return

        val area =
            when (placement) {
                Placement.FILL -> RectF(bounds)
                Placement.CENTERED_BAND -> {
                    val width = bounds.width() * BAND_WIDTH
                    val height = width / SYSTEM_ART_ASPECT
                    val left = bounds.left + (bounds.width() - width) / 2f
                    val top = bounds.top + (bounds.height() - height) / 2f
                    RectF(left, top, left + width, top + height)
                }
            }
        if (area.width() <= 0f || area.height() <= 0f) return

        val frame = (art.width * FRAME_CROP).toInt()
        val srcWidth = art.width - 2 * frame
        val srcHeight = art.height - 2 * frame
        if (srcWidth <= 0 || srcHeight <= 0) return

        val srcAspect = srcWidth.toFloat() / srcHeight
        val dstAspect = area.width() / area.height()
        if (abs(srcAspect / dstAspect - 1f) <= FILL_TOLERANCE) {
            // Standard art: fills the area, cropping what little is left over.
            val cropWidth = min(srcWidth, (srcHeight * dstAspect).toInt())
            val cropHeight = min(srcHeight, (srcWidth / dstAspect).toInt())
            val left = frame + (srcWidth - cropWidth) / 2
            val top = frame + (srcHeight - cropHeight) / 2
            src.set(left, top, left + cropWidth, top + cropHeight)
            dst.set(area)
        } else {
            // Wide art with a logo (arcade makers, SC-3000): fitted whole and centred.
            val scale = min(area.width() / srcWidth, area.height() / srcHeight) * FIT_SCALE
            val width = srcWidth * scale
            val height = srcHeight * scale
            src.set(frame, frame, frame + srcWidth, frame + srcHeight)
            val left = area.left + (area.width() - width) / 2f
            val top = area.top + (area.height() - height) / 2f
            dst.set(left, top, left + width, top + height)
        }
        canvas.drawBitmap(art, src, dst, artPaint)
    }

    override fun setAlpha(alpha: Int) {
        backgroundPaint.alpha = alpha
        artPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        artPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    private companion object {
        // Same values as LemuroidSystemImage (mobile).
        const val SYSTEM_ART_ASPECT = 277f / 192f
        const val FRAME_CROP = 0.022f
        const val FILL_TOLERANCE = 0.1f
        const val FIT_SCALE = 0.9f
        const val BAND_WIDTH = 0.9f
    }
}
