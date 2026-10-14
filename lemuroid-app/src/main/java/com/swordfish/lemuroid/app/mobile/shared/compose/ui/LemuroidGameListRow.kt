package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.utils.games.GameUtils
import com.swordfish.lemuroid.lib.library.db.entity.Game

/**
 * Game row of the list layout (catalog, search): small 3:4 cover, title, system, variants and save
 * state in line, favourite toggle. The row spans the whole width, so D-pad focus is an accent
 * background instead of the outline of the cards.
 *
 * @param saveState overrides [isDownloaded] when the caller knows the queue state
 * ([rememberCoverSaveState]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LemuroidGameListRow(
    modifier: Modifier = Modifier,
    game: Game,
    isDownloaded: Boolean = false,
    hasVariants: Boolean = false,
    saveState: CoverSaveState = if (isDownloaded) CoverSaveState.Saved else CoverSaveState.NotSaved,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavoriteToggle: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val subtitle = remember(game.id) { GameUtils.getGameSubtitle(context, game) }
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.hasFocus }
                .background(if (focused) LemuroidColors.AccentPill else Color.Transparent)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(horizontal = LocalDimens.current.screenMargin, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .width(THUMBNAIL_WIDTH)
                    .clip(RoundedCornerShape(6.dp)),
        ) {
            LemuroidGameImage(game = game, compact = true)
        }
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 8.dp),
        ) {
            Text(
                text = game.title,
                style = MaterialTheme.typography.titleSmall,
                color = LemuroidColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = LocalLemuroidTypography.current.support,
                color = LemuroidColors.TextSupport,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (hasVariants) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.variants_badge),
                tint = LemuroidColors.TextSupport,
                modifier =
                    Modifier
                        .padding(end = 8.dp)
                        .size(18.dp),
            )
        }
        SaveStateIndicator(state = saveState, modifier = Modifier.padding(end = 8.dp))
        Box(modifier = Modifier.size(40.dp)) {
            FavoriteToggle(
                isToggled = game.isFavorite,
                onFavoriteToggle = onFavoriteToggle,
            )
        }
    }
}

private val THUMBNAIL_WIDTH = 42.dp
