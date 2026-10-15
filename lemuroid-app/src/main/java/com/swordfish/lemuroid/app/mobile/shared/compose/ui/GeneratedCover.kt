package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.library.metaSystemID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cover for a game without art (38% of the catalog): dark tone of the system colour, the console
 * art blended with [BlendMode.Screen] (its black background disappears) and the title.
 *
 * Sized by the caller (3:4 inside [LemuroidGameImage]).
 *
 * @param showTitle false where the title is already printed next to the cover (catalog card,
 * list row): the art is then centred instead of sitting above the title.
 */
@Composable
fun GeneratedCover(
    game: Game,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = LocalLemuroidTypography.current.cardTitle,
    showTitle: Boolean = true,
) {
    val metaSystem = remember(game.systemId) { GameSystem.findByIdOrNull(game.systemId)?.metaSystemID() }
    val background =
        remember(metaSystem) {
            metaSystem?.let { SystemPalette.darkTone(SystemPalette.color(it)) } ?: LemuroidColors.Surface
        }
    val art = metaSystem?.let { rememberSystemArt(it.imageResId).value }

    Box(
        modifier =
            modifier
                .background(background)
                .drawBehind {
                    if (art == null) return@drawBehind
                    val width = size.width * ART_WIDTH
                    val height = width / SYSTEM_ART_ASPECT
                    val top = if (showTitle) size.height * ART_TOP else (size.height - height) / 2f
                    drawSystemArt(art, Offset((size.width - width) / 2f, top), Size(width, height))
                },
    ) {
        if (showTitle) {
            Text(
                text = game.title,
                style = titleStyle,
                color = LemuroidColors.Text,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
            )
        }
    }
}

private const val ART_WIDTH = 0.9f
private const val ART_TOP = 0.14f

/**
 * Console art (`game_system_*`), decoded off the main thread and shared by every cover of the
 * same system. Null until decoded.
 *
 * The state is keyed by [resId]: a caller that swaps the id (the focus art of the system block)
 * gets null again until the new art is decoded, never the art of the previous id.
 */
@Composable
fun rememberSystemArt(
    @DrawableRes resId: Int,
): State<ImageBitmap?> {
    val context = LocalContext.current
    val art = remember(resId) { mutableStateOf(SystemArtCache.peek(resId)) }
    LaunchedEffect(resId) {
        if (art.value == null) {
            art.value = withContext(Dispatchers.IO) { SystemArtCache.load(context, resId) }
        }
    }
    return art
}

internal object SystemArtCache {
    // Sized in bytes because the art is not uniform: 277x192 (~210 KB in ARGB, half of that in
    // RGB_565) for most systems, up to 720x332 for a few. Either budget holds ~28 standard arts:
    // the Systems screen shows 10-15 blocks at a time, about 30 on a TV box.
    private const val MAX_BYTES_FULL = 6 * 1024 * 1024
    private const val MAX_BYTES_LITE = 3 * 1024 * 1024

    @Volatile
    private var cache: LruCache<Int, ImageBitmap>? = null

    fun peek(resId: Int): ImageBitmap? = cache?.get(resId)

    fun load(
        context: Context,
        resId: Int,
    ): ImageBitmap? {
        val lite = VisualQuality.of(context).isLite
        val cache = cacheFor(lite)
        cache.get(resId)?.let { return it }
        val options =
            BitmapFactory.Options().apply {
                // The PNGs live in drawable/ without a density qualifier: without this the decoder
                // scales them to the screen density (3x on xxhdpi, ~1.9 MB each).
                inScaled = false
                if (lite) inPreferredConfig = Bitmap.Config.RGB_565
            }
        val bitmap =
            runCatching { BitmapFactory.decodeResource(context.resources, resId, options) }
                .getOrNull()
                ?.asImageBitmap()
                ?: return null
        cache.put(resId, bitmap)
        return bitmap
    }

    private fun cacheFor(lite: Boolean): LruCache<Int, ImageBitmap> =
        cache ?: synchronized(this) {
            cache ?: newCache(if (lite) MAX_BYTES_LITE else MAX_BYTES_FULL).also { cache = it }
        }

    private fun newCache(maxBytes: Int) =
        object : LruCache<Int, ImageBitmap>(maxBytes) {
            override fun sizeOf(
                key: Int,
                value: ImageBitmap,
            ): Int = value.asAndroidBitmap().byteCount
        }
}
