package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.app.shared.systems.MetaSystemInfo

@Composable
fun LemuroidSystemImage(system: MetaSystemInfo) {
    val baseColor = Color(system.metaSystem.color())
    val gradient = Brush.linearGradient(
        colors = listOf(
            Color.White,
            Color.White
        )
    )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f) // Creates a wide horizontal rectangle
                .background(gradient),
        contentAlignment = Alignment.Center,
    ) {
        // Flat 2D Image layer for realistic images
        Image(
            modifier = Modifier
                .fillMaxSize(), // Occupies 100% of the entire box area
            painter = painterResource(id = system.metaSystem.imageResId),
            contentDescription = stringResource(id = system.metaSystem.titleResId),
            contentScale = ContentScale.Fit, // Ensures logo is fully visible (not cropped) touching the box edges
        )
    }
}
