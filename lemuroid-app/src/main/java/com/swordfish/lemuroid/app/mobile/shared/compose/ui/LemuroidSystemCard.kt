package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.effects.focusHighlight
import com.swordfish.lemuroid.app.shared.systems.MetaSystemInfo

/**
 * System block of the redesign: the console art over the system colour and, in a footer in the
 * dark tone of that colour, the name and the number of games.
 */
@Composable
fun LemuroidSystemCard(
    modifier: Modifier = Modifier,
    system: MetaSystemInfo,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val dimens = LocalDimens.current
    var isFocused by remember { mutableStateOf(false) }

    val title =
        remember(system.metaSystem.titleResId) {
            system.getName(context)
        }

    val subtitle =
        remember(system.count) {
            context.getString(
                R.string.system_grid_details,
                system.count.toString(),
            )
        }

    val footerColor =
        remember(system.metaSystem) {
            SystemPalette.darkTone(SystemPalette.color(system.metaSystem))
        }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .focusHighlight()
                .onFocusChanged { isFocused = it.hasFocus }
                .clip(RoundedCornerShape(dimens.cardCornerRadius))
                .background(footerColor)
                .clickable(role = Role.Button, onClick = onClick),
    ) {
        LemuroidSystemImage(
            metaSystem = system.metaSystem,
            modifier = Modifier.fillMaxWidth(),
            hovered = isFocused,
            fadeTo = footerColor,
        )
        Column(
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = LemuroidColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = LocalLemuroidTypography.current.support,
                color = LemuroidColors.TextSupportOnBackdrop,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
