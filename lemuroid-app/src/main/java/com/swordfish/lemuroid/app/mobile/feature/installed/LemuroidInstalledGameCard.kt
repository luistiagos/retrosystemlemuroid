package com.swordfish.lemuroid.app.mobile.feature.installed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidColors
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidGameImage
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LocalDimens
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LocalLemuroidTypography
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.SystemChip
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.effects.focusHighlight

/**
 * Cover card of the Saved games grid: 3:4 cover with the system chip (top-start), unavailable
 * media and saved versions ([SavedGameBadges]), title below. No save-state badge: every game here
 * is saved.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LemuroidInstalledGameCard(
    modifier: Modifier = Modifier,
    item: InstalledGameGroupUiModel,
    onClick: () -> Unit = { },
    onLongClick: () -> Unit = { },
) {
    val game = item.group.game
    val shape = RoundedCornerShape(LocalDimens.current.cardCornerRadius)

    Column(
        modifier =
            modifier
                .focusHighlight()
                .clip(shape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(COVER_ASPECT)
                    .clip(shape)
                    .alpha(if (item.isAvailable) 1f else SAVED_GAME_UNAVAILABLE_ALPHA),
        ) {
            LemuroidGameImage(
                game = game,
                modifier = Modifier.fillMaxSize(),
                generatedCoverTitle = false,
            )
            SystemChip(
                systemId = game.systemId,
                compact = true,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        // Leaves the top-end corner to the unavailable badge (22 dp + margins).
                        .padding(start = 6.dp, top = 6.dp, end = 34.dp),
            )
            SavedGameBadges(item)
        }

        Text(
            text = game.title,
            style = LocalLemuroidTypography.current.cardTitle,
            color = LemuroidColors.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 2.dp, end = 2.dp, top = 6.dp, bottom = 2.dp),
        )
    }
}

/** Cover proportion of the redesign (3:4), as in `LemuroidGameGridItem`. */
private const val COVER_ASPECT = 0.75f
