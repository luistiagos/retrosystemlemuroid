package com.swordfish.lemuroid.app.mobile.feature.settings.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.lib.transfer.TransferProgress

@Composable
fun TransferImportScreen(
    modifier: Modifier = Modifier,
    viewModel: TransferViewModel,
) {
    val state = viewModel.uiState.collectAsState().value

    LaunchedEffect(Unit) {
        viewModel.checkForImportableMedia()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        when (val progress = state.importProgress) {
            is TransferProgress.Idle -> {
                if (state.importManifest != null) {
                    ImportAvailable(state, viewModel)
                } else {
                    ImportNotFound()
                }
            }
            is TransferProgress.InProgress -> {
                ImportInProgress(progress)
            }
            is TransferProgress.Completed -> {
                ImportCompleted(progress.gamesExported)
            }
            is TransferProgress.Error -> {
                ImportError(progress.message)
            }
        }
    }
}

@Composable
private fun ImportAvailable(
    state: TransferViewModel.UiState,
    viewModel: TransferViewModel,
) {
    val manifest = state.importManifest ?: return

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.transfer_import_found),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.transfer_import_games_count, manifest.games.size),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.transfer_import_from_version, manifest.appVersion),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = { viewModel.startImport() },
            modifier = Modifier.fillMaxWidth(0.6f),
        ) {
            Text(text = stringResource(R.string.transfer_import_button))
        }
    }
}

@Composable
private fun ImportNotFound() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.transfer_import_not_found),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.transfer_import_not_found_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImportInProgress(progress: TransferProgress.InProgress) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.transfer_importing),
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
private fun ImportCompleted(count: Int) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.transfer_import_completed, count),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun ImportError(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.transfer_import_error),
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
