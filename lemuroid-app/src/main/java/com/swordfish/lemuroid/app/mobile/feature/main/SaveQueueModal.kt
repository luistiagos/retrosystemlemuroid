package com.swordfish.lemuroid.app.mobile.feature.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.BottomSheetHeader
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidBottomSheet
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidColors
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LocalLemuroidTypography
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.SheetMargin
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.SystemPalette
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.effects.focusHighlight
import com.swordfish.lemuroid.app.shared.roms.SaveQueueEntry
import com.swordfish.lemuroid.app.shared.roms.SaveQueueState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveQueueModal(
    viewModel: SaveQueueViewModel,
    onDismiss: () -> Unit,
) {
    val entries by viewModel.entries.collectAsState()
    val hasErrors by viewModel.hasErrors.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    LemuroidBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        BottomSheetHeader(title = stringResource(R.string.save_queue_title)) {
            if (hasErrors) {
                TextButton(
                    onClick = { viewModel.clearErrors() },
                    modifier = Modifier.focusHighlight(cornerRadius = 20.dp),
                ) {
                    Text(stringResource(R.string.save_queue_clear_errors))
                }
            }
        }

        if (entries.isEmpty()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = null,
                    tint = LemuroidColors.TextSupport,
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    text = stringResource(R.string.save_queue_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LemuroidColors.TextSupport,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            ) {
                items(entries, key = { it.fileName }) { entry ->
                    SaveQueueItemRow(
                        entry = entry,
                        onPause = { viewModel.pauseActive() },
                        onResume = { viewModel.resumeActive() },
                        onCancel = {
                            if (entry.state == SaveQueueState.ERROR) {
                                viewModel.dismissError(entry.fileName)
                            } else {
                                viewModel.cancel(entry.fileName)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SaveQueueItemRow(
    entry: SaveQueueEntry,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    // Shows through while the cover loads, and stays when there is none (offline, no art).
    val coverBackground =
        remember(entry.systemId) { SystemPalette.darkTone(SystemPalette.colorForSystemId(entry.systemId)) }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = SheetMargin, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(COVER_WIDTH, COVER_HEIGHT)
                        .clip(RoundedCornerShape(6.dp))
                        .background(coverBackground),
            ) {
                AsyncImage(
                    model =
                        ImageRequest.Builder(context)
                            .data(entry.coverUrl)
                            .build(),
                    contentDescription = entry.title,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = LemuroidColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = statusLabel(entry),
                    style = LocalLemuroidTypography.current.support,
                    color =
                        when (entry.state) {
                            SaveQueueState.SAVING -> LemuroidColors.Accent
                            SaveQueueState.ERROR -> MaterialTheme.colorScheme.error
                            else -> LemuroidColors.TextSupport
                        },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Action buttons
            when (entry.state) {
                SaveQueueState.SAVING ->
                    RowAction(Icons.Default.Pause, stringResource(R.string.save_action_pause), onPause)
                SaveQueueState.PAUSED ->
                    RowAction(Icons.Default.PlayArrow, stringResource(R.string.save_action_resume), onResume)
                else -> {}
            }

            if (entry.state != SaveQueueState.SAVED) {
                RowAction(Icons.Default.Clear, stringResource(R.string.save_action_cancel), onCancel)
            }
        }

        // Progress bar for active downloads
        if (entry.state == SaveQueueState.SAVING || entry.state == SaveQueueState.PAUSED) {
            Spacer(Modifier.height(8.dp))
            val barModifier =
                Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
            if (entry.progress > 0f) {
                LinearProgressIndicator(
                    progress = { entry.progress },
                    modifier = barModifier,
                    color =
                        if (entry.state == SaveQueueState.PAUSED) LemuroidColors.TextSupport else LemuroidColors.Accent,
                    trackColor = LemuroidColors.Border,
                )
            } else {
                LinearProgressIndicator(
                    modifier = barModifier,
                    color = LemuroidColors.Accent,
                    trackColor = LemuroidColors.Border,
                )
            }
        }
    }
}

@Composable
private fun RowAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.focusHighlight(cornerRadius = 20.dp),
    ) {
        Icon(icon, contentDescription = description, tint = LemuroidColors.Text)
    }
}

@Composable
private fun statusLabel(entry: SaveQueueEntry): String =
    when (entry.state) {
        SaveQueueState.QUEUED -> stringResource(R.string.save_queue_waiting)
        SaveQueueState.SAVING ->
            if (entry.progress > 0f) {
                stringResource(R.string.save_queue_saving_progress, (entry.progress * 100).toInt())
            } else {
                stringResource(R.string.save_queue_saving)
            }
        SaveQueueState.PAUSED -> stringResource(R.string.save_queue_paused)
        SaveQueueState.SAVED -> stringResource(R.string.save_queue_done)
        SaveQueueState.ERROR ->
            entry.errorMessage
                ?: stringResource(R.string.save_queue_error)
    }

private val COVER_WIDTH = 48.dp
private val COVER_HEIGHT = 64.dp
