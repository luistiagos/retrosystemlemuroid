package com.swordfish.lemuroid.app.mobile.feature.main

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.BottomSheetHeader
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidBottomSheet
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidColors
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidGameImage
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.SaveStateIndicator
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.SheetMargin
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.rememberCoverSaveState
import com.swordfish.lemuroid.app.shared.roms.SaveQueueEntry
import com.swordfish.lemuroid.lib.library.db.entity.Game

/**
 * @param saveQueueEntries queue entries by download key (`SaveQueueViewModel.entriesByKey`): with
 * it a variant in the queue shows its state; without it only "saved" is shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameVariantsModal(
    game: Game,
    variants: List<Game>,
    downloadedGameKeys: Set<String>,
    onDismiss: () -> Unit,
    onVariantSelected: (Game) -> Unit,
    onVariantLongClick: (Game) -> Unit,
    saveQueueEntries: State<Map<String, SaveQueueEntry>>? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    LemuroidBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        BottomSheetHeader(
            title = game.title,
            subtitle = stringResource(R.string.variants_subtitle, variants.size),
        )

        LazyColumn(modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)) {
            items(variants, key = { it.id }) { variant ->
                VariantRow(
                    variant = variant,
                    coverUrl = game.coverFrontUrl,
                    isDownloaded = downloadedGameKeys.contains(variant.downloadKey),
                    saveQueueEntries = saveQueueEntries,
                    onClick = {
                        onDismiss()
                        onVariantSelected(variant)
                    },
                    onLongClick = {
                        onDismiss()
                        onVariantLongClick(variant)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VariantRow(
    variant: Game,
    coverUrl: String?,
    isDownloaded: Boolean,
    saveQueueEntries: State<Map<String, SaveQueueEntry>>?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // Every row shows the cover of the representative (the variants rarely have their own); without
    // it, the generated cover of the system.
    val coverGame = remember(variant, coverUrl) { variant.copy(coverFrontUrl = coverUrl) }
    val saveState = rememberCoverSaveState(variant.downloadKey, isDownloaded, saveQueueEntries)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.hasFocus }
                .background(if (focused) LemuroidColors.AccentPill else Color.Transparent)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(horizontal = SheetMargin, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .width(COVER_WIDTH)
                    .clip(RoundedCornerShape(6.dp)),
        ) {
            LemuroidGameImage(game = coverGame, compact = true)
        }

        Text(
            text = fileNameWithoutExtension(variant.fileName),
            style = MaterialTheme.typography.bodyMedium,
            color = LemuroidColors.Text,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        SaveStateIndicator(state = saveState)
    }
}

private fun fileNameWithoutExtension(fileName: String): String {
    val dotIndex = fileName.lastIndexOf('.')
    return if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
}

private val COVER_WIDTH = 36.dp
