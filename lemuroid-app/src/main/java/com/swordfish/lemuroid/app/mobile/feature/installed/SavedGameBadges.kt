package com.swordfish.lemuroid.app.mobile.feature.installed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidColors
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LocalLemuroidTypography
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.PillShape

/** Alpha of the cover of a saved game whose media is not reachable (R4). */
const val SAVED_GAME_UNAVAILABLE_ALPHA = 0.6f

/**
 * Badges of a saved game over its cover (Saved games tab and its row on the Home): unavailable
 * media (top-end) and the number of saved versions when more than one (bottom-end).
 */
@Composable
fun BoxScope.SavedGameBadges(item: InstalledGameGroupUiModel) {
    if (!item.isAvailable) {
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .background(LemuroidColors.ErrorBadge, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = stringResource(R.string.installed_media_unavailable),
                tint = Color.White,
                modifier = Modifier.size(13.dp),
            )
        }
    }
    if (item.group.installedVariantsCount > 1) {
        Row(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(LemuroidColors.Bars, PillShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Layers,
                contentDescription = null,
                tint = LemuroidColors.Text,
                modifier = Modifier.size(11.dp),
            )
            Text(
                text = " ${item.group.installedVariantsCount}",
                style = LocalLemuroidTypography.current.support,
                fontWeight = FontWeight.Bold,
                color = LemuroidColors.Text,
            )
        }
    }
}
