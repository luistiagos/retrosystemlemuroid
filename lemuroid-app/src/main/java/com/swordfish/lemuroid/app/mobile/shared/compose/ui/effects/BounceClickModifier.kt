package com.swordfish.lemuroid.app.mobile.shared.compose.ui.effects

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback

fun Modifier.bounceClick(
    enabled: Boolean = true,
    hapticFeedbackEnabled: Boolean = true,
    scaleDownPercentage: Float = 0.90f,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) = composed {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) scaleDownPercentage else 1f,
        animationSpec = spring(
            dampingRatio = 0.5f,
            stiffness = 500f
        ),
        label = "bounceAnim"
    )

    val haptic = LocalHapticFeedback.current

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(enabled, onLongClick) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = { _ ->
                    isPressed = true
                    if (hapticFeedbackEnabled) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    tryAwaitRelease()
                    isPressed = false
                },
                onTap = { onClick() },
                onLongPress = onLongClick?.let { longClick -> { _ -> longClick() } },
            )
        }
}
