package com.swordfish.lemuroid.app.mobile.shared.compose.ui.effects

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidColors
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LocalDimens
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LocalVisualQuality

/**
 * D-pad focus of the redesign: accent outline [Dimens.focusBorderWidth] wide, drawn
 * [Dimens.focusBorderGap] outside the item, plus [Dimens.focusScale] (animated only in
 * [VisualQuality.FULL]).
 *
 * Put it **before** the clickable/focusable modifier in the chain, so it observes that node's focus,
 * and before any `clip`, so the outline is not clipped.
 *
 * The outline and the scale fall outside the item bounds, which is all a scrolling list brings into
 * view when focus moves: on focus this also asks the list to show the outline (otherwise its lower
 * edge stays under the bottom of the grid).
 *
 * @param cornerRadius radius of the item; the outline follows it, grown by the gap.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.focusHighlight(
    cornerRadius: Dp? = null,
    color: Color = LemuroidColors.Accent,
) = composed {
    val dimens = LocalDimens.current
    val density = LocalDensity.current
    val lite = LocalVisualQuality.current.isLite
    var focused by remember { mutableStateOf(false) }
    val targetScale = if (focused) dimens.focusScale else 1f
    val scale =
        if (lite) {
            targetScale
        } else {
            animateFloatAsState(targetScale, tween(FOCUS_ANIMATION_MS), label = "focusScale").value
        }
    val radius = cornerRadius ?: dimens.cardCornerRadius
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    // Only read in the focus callback, never during composition: plain holder, no State.
    val nodeSize = remember { IntArray(2) }

    this
        .bringIntoViewRequester(bringIntoView)
        .onSizeChanged {
            nodeSize[0] = it.width
            nodeSize[1] = it.height
        }
        .onFocusChanged {
            focused = it.hasFocus
            if (it.hasFocus) {
                val width = nodeSize[0].toFloat()
                val height = nodeSize[1].toFloat()
                val margin =
                    with(density) { (dimens.focusBorderGap + dimens.focusBorderWidth).toPx() } +
                        (dimens.focusScale - 1f) / 2f * maxOf(width, height)
                scope.launch { bringIntoView.bringIntoView(Rect(-margin, -margin, width + margin, height + margin)) }
            }
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .drawWithContent {
            drawContent()
            if (!focused) return@drawWithContent
            val stroke = dimens.focusBorderWidth.toPx()
            val inset = dimens.focusBorderGap.toPx() + stroke / 2f
            drawRoundRect(
                color = color,
                topLeft = Offset(-inset, -inset),
                size = Size(size.width + inset * 2, size.height + inset * 2),
                cornerRadius = CornerRadius(radius.toPx() + inset),
                style = Stroke(width = stroke),
            )
        }
}

private const val FOCUS_ANIMATION_MS = 120
