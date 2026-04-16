package com.swordfish.lemuroid.app.mobile.feature.settings.transfer

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.lib.transfer.TransferProgress

@Composable
fun TransferExportScreen(
    modifier: Modifier = Modifier,
    viewModel: TransferViewModel,
) {
    val context = LocalContext.current
    val state = viewModel.uiState.collectAsState().value

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            viewModel.startExportToUri(uri)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        when (val progress = state.exportProgress) {
            is TransferProgress.Idle -> {
                if (state.isLoading) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    ExportConfiguration(
                        state = state,
                        viewModel = viewModel,
                        onExport = { folderPicker.launch(null) },
                    )
                }
            }
            is TransferProgress.InProgress -> {
                ExportInProgress(progress)
            }
            is TransferProgress.Completed -> {
                ExportCompleted(progress.gamesExported)
            }
            is TransferProgress.Error -> {
                ExportError(progress.message)
            }
        }
    }
}

@Composable
private fun ColumnScope.ExportConfiguration(
    state: TransferViewModel.UiState,
    viewModel: TransferViewModel,
    onExport: () -> Unit,
) {
    val context = LocalContext.current

    // Include APK toggle
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.transfer_include_apk),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.transfer_include_apk_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = state.includeApk,
            onCheckedChange = { viewModel.setIncludeApk(it) },
        )
    }

    // Select/Deselect all
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.transfer_games_selected, state.selectedGameIds.size, state.allGames.size),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { viewModel.selectAllGames() }) {
            Text(text = stringResource(R.string.transfer_select_all))
        }
        TextButton(onClick = { viewModel.deselectAllGames() }) {
            Text(text = stringResource(R.string.transfer_deselect_all))
        }
    }

    // Game list
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
    ) {
        items(state.allGames, key = { it.id }) { game ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.toggleGameSelection(game.id) }
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = game.id in state.selectedGameIds,
                    onCheckedChange = { viewModel.toggleGameSelection(game.id) },
                )
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(text = game.title, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = game.systemId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Size info
    Text(
        text = stringResource(
            R.string.transfer_export_size,
            Formatter.formatFileSize(context, state.exportSizeBytes),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(12.dp))

    Button(
        onClick = onExport,
        modifier = Modifier.fillMaxWidth(),
        enabled = state.selectedGameIds.isNotEmpty(),
    ) {
        Text(text = stringResource(R.string.transfer_export_button))
    }
}

@Composable
private fun ExportInProgress(progress: TransferProgress.InProgress) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.transfer_exporting),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = progress.currentGameName,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { progress.progressFraction },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "${progress.currentIndex} / ${progress.totalCount}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ExportCompleted(count: Int) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.transfer_export_completed, count),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun ExportError(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.transfer_export_error),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
