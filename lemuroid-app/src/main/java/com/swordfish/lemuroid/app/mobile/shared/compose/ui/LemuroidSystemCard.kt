package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.systems.MetaSystemInfo

@Composable
fun LemuroidSystemCard(
    modifier: Modifier = Modifier,
    system: MetaSystemInfo,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var isFocused by remember { mutableStateOf(false) }

    val title =
        remember(system.metaSystem.titleResId) {
            system.getName(context)
        }

    val subtitle =
        remember(system.metaSystem.titleResId) {
            context.getString(
                R.string.system_grid_details,
                system.count.toString(),
            )
        }

    ElevatedCard(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .onFocusChanged { isFocused = it.hasFocus },
        onClick = onClick,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 12.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
        )
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth(),
        ) {
            LemuroidSystemImage(system, hovered = isFocused)
            LemuroidTexts(title = title, subtitle = subtitle)
        }
    }
}
