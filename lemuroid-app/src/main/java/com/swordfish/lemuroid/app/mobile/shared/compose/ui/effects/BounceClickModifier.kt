package com.swordfish.lemuroid.app.mobile.shared.compose.ui.effects

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import com.swordfish.lemuroid.app.utils.android.isTvDevice

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.bounceClick(
    enabled: Boolean = true,
    hapticFeedbackEnabled: Boolean = true,
    scaleDownPercentage: Float = 0.90f,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) = composed {
    val isTv = LocalContext.current.isTvDevice()

    if (isTv) {
        // On TV/TV-box: simple click without animation or haptic feedback
        this.combinedClickable(
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    } else {
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
}
