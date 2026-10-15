package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swordfish.lemuroid.lib.library.GameSystem

/**
 * Short name of the system in a pill of its colour ([SystemPalette]). [compact] is the size that
 * sits over a grid cover.
 */
@Composable
fun SystemChip(
    systemId: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    val name =
        remember(systemId) {
            GameSystem.findByIdOrNull(systemId)?.shortTitleResId?.let { context.getString(it) } ?: systemId
        }
    val color = remember(systemId) { SystemPalette.colorForSystemId(systemId) }
    val support = LocalLemuroidTypography.current.support
    Text(
        text = name,
        style = if (compact) support.copy(fontSize = 10.sp, lineHeight = 12.sp) else support,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier
                .background(color, PillShape)
                .padding(
                    horizontal = if (compact) 6.dp else 10.dp,
                    vertical = if (compact) 2.dp else 3.dp,
                ),
    )
}
