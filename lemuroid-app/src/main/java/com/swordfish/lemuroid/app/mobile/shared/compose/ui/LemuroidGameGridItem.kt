package com.swordfish.lemuroid.app.mobile.shared.compose.ui

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.effects.focusHighlight
import com.swordfish.lemuroid.lib.library.db.entity.Game

/**
 * Cover card of the catalog and Favorites grids: 3:4 cover with the save state (top-end) and the variants badge
 * (bottom-end), title below.
 *
 * @param saveState overrides [isDownloaded] when the caller knows the queue state
 * ([rememberCoverSaveState]).
 * @param showSystemChip the system chip at the top-start corner, for grids that mix systems
 * (Favorites); the catalog is a single system and leaves it off.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LemuroidGameGridItem(
    modifier: Modifier = Modifier,
    game: Game,
    isDownloaded: Boolean = false,
    hasVariants: Boolean = false,
    saveState: CoverSaveState = if (isDownloaded) CoverSaveState.Saved else CoverSaveState.NotSaved,
    showSystemChip: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
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
                    .clip(shape),
        ) {
            LemuroidGameImage(
                game = game,
                modifier = Modifier.fillMaxSize(),
                generatedCoverTitle = false,
            )
            if (showSystemChip) {
                SystemChip(
                    systemId = game.systemId,
                    compact = true,
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            // Leaves the top-end corner to the save-state badge (22 dp + margins).
                            .padding(start = 6.dp, top = 6.dp, end = 34.dp),
                )
            }
            SaveStateOverlay(saveState)
            if (hasVariants) {
                VariantsBadge()
            }
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

/** Cover proportion of the redesign (3:4). */
private const val COVER_ASPECT = 0.75f
