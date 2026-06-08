package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.swordfish.lemuroid.app.shared.systems.MetaSystemInfo
import com.swordfish.lemuroid.lib.library.SystemLogoResolver

@Composable
fun LemuroidSystemImage(system: MetaSystemInfo, hovered: Boolean = false) {
    val gradient = Brush.linearGradient(
        colors = listOf(
            Color(0xFF1A1A2E),
            Color(0xFF16213E)
        )
    )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f)
                .background(gradient),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            modifier = Modifier
                .fillMaxSize(),
            painter = painterResource(id = SystemLogoResolver.resolve(system.metaSystem, hovered)),
            contentDescription = stringResource(id = system.metaSystem.titleResId),
            contentScale = ContentScale.Fit,
        )
    }
}
