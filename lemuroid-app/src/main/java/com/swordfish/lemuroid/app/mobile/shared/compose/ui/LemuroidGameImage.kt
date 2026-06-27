package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.swordfish.lemuroid.app.shared.covers.CoverUtils
import com.swordfish.lemuroid.lib.library.db.entity.Game

@Composable
fun LemuroidGameImage(
    modifier: Modifier = Modifier,
    game: Game,
) {
    val fallbackDrawable =
        remember(game) {
            CoverUtils.getFallbackDrawable(game)
        }

    val fallbackPainter = rememberDrawablePainter(drawable = fallbackDrawable)

    val context = LocalContext.current
    // On weak devices decode covers smaller (less bitmap memory per item) and skip the
    // crossfade (no second bitmap held during the animation). Computed once per device.
    val lowRam = remember { CoverUtils.isLowRamDevice(context) }
    val imageRequest = remember(game.coverFrontUrl, lowRam) {
        ImageRequest.Builder(context)
            .data(game.coverFrontUrl)
            .crossfade(!lowRam)
            .size(if (lowRam) 256 else 400)
            .build()
    }

    AsyncImage(
        model = imageRequest,
        contentDescription = game.title,
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(0.75f),
        fallback = fallbackPainter,
        error = fallbackPainter,
        contentScale = ContentScale.Crop,
    )
}

