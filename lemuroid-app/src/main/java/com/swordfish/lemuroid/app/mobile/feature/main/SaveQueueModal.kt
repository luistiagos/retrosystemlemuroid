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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.swordfish.lemuroid.R
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.save_queue_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (hasErrors) {
                TextButton(onClick = { viewModel.clearErrors() }) {
                    Text(stringResource(R.string.save_queue_clear_errors))
                }
            }
        }
        HorizontalDivider()

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.save_queue_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(bottom = 16.dp),
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
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp, 72.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
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
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = statusLabel(entry),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Action buttons
            when (entry.state) {
                SaveQueueState.SAVING -> {
                    IconButton(onClick = onPause) {
                        Icon(Icons.Default.Pause, contentDescription = stringResource(R.string.save_action_pause))
                    }
                }
                SaveQueueState.PAUSED -> {
                    IconButton(onClick = onResume) {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.save_action_resume))
                    }
                }
                else -> {}
            }

            if (entry.state != SaveQueueState.SAVED) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.save_action_cancel))
                }
            }
        }

        // Progress bar for active downloads
        if (entry.state == SaveQueueState.SAVING || entry.state == SaveQueueState.PAUSED) {
            Spacer(Modifier.height(6.dp))
            if (entry.progress > 0f) {
                LinearProgressIndicator(
                    progress = { entry.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun statusLabel(entry: SaveQueueEntry): String = when (entry.state) {
    SaveQueueState.QUEUED -> stringResource(R.string.save_queue_waiting)
    SaveQueueState.SAVING -> if (entry.progress > 0f)
        stringResource(R.string.save_queue_saving_progress, (entry.progress * 100).toInt())
    else
        stringResource(R.string.save_queue_saving)
    SaveQueueState.PAUSED -> stringResource(R.string.save_queue_paused)
    SaveQueueState.SAVED -> stringResource(R.string.save_queue_done)
    SaveQueueState.ERROR -> entry.errorMessage
        ?: stringResource(R.string.save_queue_error)
}
