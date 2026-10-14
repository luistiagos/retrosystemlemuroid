package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Ambient background of the redesign: the art of the featured/focused item behind a gradient that
 * fades into the page.
 *
 * `Modifier.blur` only works on Android 12+, so the "blur" is the image decoded at
 * [BACKDROP_DECODE_PX] and stretched with bilinear filtering. In [VisualQuality.LITE] (or without
 * [model]) there is no image at all, only a gradient in the dark tone of [systemColor].
 *
 * @param model anything Coil loads: a cover URL or a drawable id (system art, in the catalog).
 */
@Composable
fun AmbientBackdrop(
    model: Any?,
    systemColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val quality = LocalVisualQuality.current
    Box(modifier = modifier.background(LemuroidColors.Background)) {
        if (quality == VisualQuality.FULL && model != null) {
            val context = LocalContext.current
            val request =
                remember(model) {
                    ImageRequest.Builder(context)
                        .data(model)
                        .size(BACKDROP_DECODE_PX)
                        .crossfade(true)
                        .build()
                }
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
                alpha = 0.6f,
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(SystemPalette.darkTone(systemColor), LemuroidColors.Background),
                            ),
                        ),
            )
        }
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to LemuroidColors.Background.copy(alpha = 0.35f),
                            0.6f to LemuroidColors.Background.copy(alpha = 0.8f),
                            1f to LemuroidColors.Background,
                        ),
                    ),
        )
        content()
    }
}

private const val BACKDROP_DECODE_PX = 32
