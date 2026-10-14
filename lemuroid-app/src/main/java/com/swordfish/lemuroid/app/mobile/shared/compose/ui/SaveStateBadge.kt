package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.roms.SaveQueueEntry
import com.swordfish.lemuroid.app.shared.roms.SaveQueueState

/**
 * Save ("download") state shown on a cover. See "Download sob demanda e Jogos salvos no visual
 * novo" in docs/backlogs/2026-10-13-redesenho-visual-inspirado-pegasus.md.
 */
@Immutable
sealed interface CoverSaveState {
    data object NotSaved : CoverSaveState

    data object Queued : CoverSaveState

    /** [percent] is already rounded to 1%, so equal values do not recompose the cover. */
    data class Saving(val percent: Int) : CoverSaveState

    data object Paused : CoverSaveState

    data object Error : CoverSaveState

    data object Saved : CoverSaveState

    companion object {
        /**
         * @param entry the queue entry whose key (`"$systemId/$fileName"`) matches `Game.downloadKey`.
         * @param isDownloaded whether the key is in `downloadedGameKeys`.
         */
        fun of(
            entry: SaveQueueEntry?,
            isDownloaded: Boolean,
        ): CoverSaveState =
            when (entry?.state) {
                SaveQueueState.QUEUED -> Queued
                SaveQueueState.SAVING -> Saving((entry.progress * 100).toInt().coerceIn(0, 100))
                SaveQueueState.PAUSED -> Paused
                SaveQueueState.ERROR -> Error
                SaveQueueState.SAVED -> Saved
                null -> if (isDownloaded) Saved else NotSaved
            }
    }
}

/**
 * Save state of the game with [downloadKey], read inside the item.
 *
 * The queue progress changes several times per second: reading [entries] through `derivedStateOf`
 * recomposes only the item whose state (rounded to 1%) changed, never the whole grid. Pass the
 * `State` itself down to the item, not its value.
 *
 * @param entries queue entries by `"$systemId/$fileName"` (`SaveQueueViewModel.entriesByKey`);
 * null = no queue information, only [isDownloaded] counts.
 */
@Composable
fun rememberCoverSaveState(
    downloadKey: String,
    isDownloaded: Boolean,
    entries: State<Map<String, SaveQueueEntry>>?,
): CoverSaveState {
    val state =
        remember(downloadKey, isDownloaded, entries) {
            derivedStateOf { CoverSaveState.of(entries?.value?.get(downloadKey), isDownloaded) }
        }
    return state.value
}

/**
 * Draws [state] over a cover. Call inside the cover's `Box`: the badge goes to the top-end corner;
 * [CoverSaveState.Saving] darkens the whole cover and shows a ring with the percentage.
 */
@Composable
fun BoxScope.SaveStateOverlay(state: CoverSaveState) {
    when (state) {
        CoverSaveState.NotSaved -> Unit
        CoverSaveState.Queued ->
            CoverBadge(
                icon = Icons.Default.Schedule,
                description = stringResource(R.string.save_queue_waiting),
                background = LemuroidColors.Bars,
                tint = LemuroidColors.Text,
                bordered = true,
            )
        CoverSaveState.Paused ->
            CoverBadge(
                icon = Icons.Default.Pause,
                description = stringResource(R.string.save_queue_paused),
                background = LemuroidColors.Bars,
                tint = LemuroidColors.Text,
                bordered = true,
            )
        CoverSaveState.Error ->
            CoverBadge(
                icon = Icons.Default.PriorityHigh,
                description = stringResource(R.string.save_queue_error),
                background = LemuroidColors.ErrorBadge,
                tint = Color.White,
            )
        CoverSaveState.Saved ->
            CoverBadge(
                icon = Icons.Default.Check,
                description = stringResource(R.string.save_queue_done),
                background = LemuroidColors.Accent,
                tint = LemuroidColors.OnAccent,
            )
        is CoverSaveState.Saving -> SavingOverlay(state.percent)
    }
}

/**
 * [state] in a line of text (list rows), where the cover is too small for [SaveStateOverlay].
 * Draws nothing for [CoverSaveState.NotSaved].
 */
@Composable
fun SaveStateIndicator(
    state: CoverSaveState,
    modifier: Modifier = Modifier,
) {
    when (state) {
        CoverSaveState.NotSaved -> Unit
        CoverSaveState.Queued ->
            InlineIcon(Icons.Default.Schedule, R.string.save_queue_waiting, LemuroidColors.TextSupport, modifier)
        CoverSaveState.Paused ->
            InlineIcon(Icons.Default.Pause, R.string.save_queue_paused, LemuroidColors.TextSupport, modifier)
        CoverSaveState.Error ->
            InlineIcon(Icons.Default.PriorityHigh, R.string.save_queue_error, MaterialTheme.colorScheme.error, modifier)
        CoverSaveState.Saved ->
            InlineIcon(Icons.Default.Check, R.string.save_queue_done, LemuroidColors.Accent, modifier)
        is CoverSaveState.Saving ->
            Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    progress = { state.percent / 100f },
                    modifier = Modifier.size(18.dp),
                    color = LemuroidColors.Accent,
                    trackColor = LemuroidColors.Text.copy(alpha = 0.25f),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "${state.percent}%",
                    style = LocalLemuroidTypography.current.support,
                    color = LemuroidColors.Text,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
    }
}

@Composable
private fun InlineIcon(
    icon: ImageVector,
    @StringRes description: Int,
    tint: Color,
    modifier: Modifier,
) {
    Icon(
        imageVector = icon,
        contentDescription = stringResource(description),
        tint = tint,
        modifier = modifier.size(20.dp),
    )
}

/** "Multiple versions" badge, bottom-end corner of the cover. */
@Composable
fun BoxScope.VariantsBadge() {
    CoverBadge(
        icon = Icons.Default.ContentCopy,
        description = stringResource(R.string.variants_badge),
        background = LemuroidColors.Bars,
        tint = LemuroidColors.Text,
        alignment = Alignment.BottomEnd,
        iconSize = 12,
    )
}

@Composable
private fun BoxScope.SavingOverlay(percent: Int) {
    Box(
        modifier =
            Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = 0.55f)),
    )
    Box(
        modifier =
            Modifier
                .align(Alignment.Center)
                .size(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.matchParentSize(),
            color = LemuroidColors.Accent,
            trackColor = LemuroidColors.Text.copy(alpha = 0.25f),
            strokeWidth = 3.dp,
        )
        Text(
            text = "$percent%",
            style = LocalLemuroidTypography.current.support,
            fontWeight = FontWeight.Bold,
            color = LemuroidColors.Text,
        )
    }
}

@Composable
private fun BoxScope.CoverBadge(
    icon: ImageVector,
    description: String,
    background: Color,
    tint: Color,
    alignment: Alignment = Alignment.TopEnd,
    bordered: Boolean = false,
    iconSize: Int = 14,
) {
    Box(
        modifier =
            Modifier
                .align(alignment)
                .padding(6.dp)
                .size(22.dp)
                .background(background, CircleShape)
                .let { if (bordered) it.border(1.dp, LemuroidColors.Border, CircleShape) else it },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(iconSize.dp),
        )
    }
}
