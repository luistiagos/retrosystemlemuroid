package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.swordfish.lemuroid.lib.library.MetaSystemID
import com.swordfish.lemuroid.lib.library.SystemLogoResolver
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Console art of [metaSystem] over the system colour, in the proportion of the art itself
 * (277:192). The width comes from the caller.
 *
 * The art is blended with [BlendMode.Screen], so its black background becomes the system colour,
 * and the white frame baked into the PNG is cropped away.
 *
 * @param hovered shows the focus art of the system ([SystemLogoResolver]); the normal art stays
 * on screen until the focus one is decoded.
 * @param fadeTo colour the lower part of the art fades into (the footer of the system block).
 * Unspecified = no fade.
 */
@Composable
fun LemuroidSystemImage(
    metaSystem: MetaSystemID,
    modifier: Modifier = Modifier,
    hovered: Boolean = false,
    fadeTo: Color = Color.Unspecified,
) {
    val color = remember(metaSystem) { SystemPalette.color(metaSystem) }
    val normalArt = rememberSystemArt(metaSystem.imageResId).value
    val focusArt =
        if (hovered) rememberSystemArt(SystemLogoResolver.resolve(metaSystem, true)).value else null
    val art = focusArt ?: normalArt
    val fade =
        remember(fadeTo) {
            if (fadeTo.isSpecified) {
                // Starts at the same hue with no alpha: a plain transparent start is black and
                // would darken the middle of the gradient.
                Brush.verticalGradient(FADE_START to fadeTo.copy(alpha = 0f), 1f to fadeTo)
            } else {
                null
            }
        }

    Box(
        modifier =
            modifier
                .aspectRatio(SYSTEM_ART_ASPECT)
                .drawBehind {
                    drawRect(color)
                    if (art != null) drawSystemArt(art)
                    if (fade != null) drawRect(fade)
                },
    )
}

/**
 * Draws the console art inside the area at [topLeft] with [area] size (the whole draw area by
 * default), blended with [BlendMode.Screen] and without the white frame baked into the PNG.
 * Shared with [GeneratedCover].
 */
internal fun DrawScope.drawSystemArt(
    art: ImageBitmap,
    topLeft: Offset = Offset.Zero,
    area: Size = size,
) {
    val frame = (art.width * FRAME_CROP).roundToInt()
    val srcWidth = art.width - 2 * frame
    val srcHeight = art.height - 2 * frame
    if (srcWidth <= 0 || srcHeight <= 0 || area.width <= 0f || area.height <= 0f) return

    val srcAspect = srcWidth.toFloat() / srcHeight
    val dstAspect = area.width / area.height
    if (abs(srcAspect / dstAspect - 1f) <= FILL_TOLERANCE) {
        // Standard art: fills the area, cropping what little is left over.
        val cropWidth = min(srcWidth, (srcHeight * dstAspect).roundToInt())
        val cropHeight = min(srcHeight, (srcWidth / dstAspect).roundToInt())
        drawImage(
            image = art,
            srcOffset = IntOffset(frame + (srcWidth - cropWidth) / 2, frame + (srcHeight - cropHeight) / 2),
            srcSize = IntSize(cropWidth, cropHeight),
            dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
            dstSize = IntSize(area.width.roundToInt(), area.height.roundToInt()),
            blendMode = BlendMode.Screen,
        )
    } else {
        // Ten systems (arcade makers, SC-3000) have wide art with a logo: cropping the sides
        // would cut it, so it is fitted whole and centred.
        val scale = min(area.width / srcWidth, area.height / srcHeight) * FIT_SCALE
        val width = srcWidth * scale
        val height = srcHeight * scale
        drawImage(
            image = art,
            srcOffset = IntOffset(frame, frame),
            srcSize = IntSize(srcWidth, srcHeight),
            dstOffset =
                IntOffset(
                    (topLeft.x + (area.width - width) / 2f).roundToInt(),
                    (topLeft.y + (area.height - height) / 2f).roundToInt(),
                ),
            dstSize = IntSize(width.roundToInt(), height.roundToInt()),
            blendMode = BlendMode.Screen,
        )
    }
}

/** Proportion of the standard `game_system_*` art (277x192). */
const val SYSTEM_ART_ASPECT = 277f / 192f

// The frame is 3 px of 277 (4 of 333 in the focus art) plus a rounded corner: 2.2% of the width
// on each side removes it completely.
private const val FRAME_CROP = 0.022f
private const val FILL_TOLERANCE = 0.1f
private const val FIT_SCALE = 0.9f
private const val FADE_START = 0.7f
