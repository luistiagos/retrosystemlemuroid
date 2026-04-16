package com.swordfish.lemuroid.app.mobile.feature.settings.romset

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.lib.romset.RomsetProgress

@Composable
fun RomsetImportScreen(
    modifier: Modifier = Modifier,
    viewModel: RomsetViewModel,
) {
    val state = viewModel.uiState.collectAsState().value

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            viewModel.startImportFromUri(uri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.checkForImportableMedia()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        when (val progress = state.importProgress) {
            is RomsetProgress.Idle -> {
                RomsetImportIdle(
                    isSearching = state.isSearchingMedia,
                    zipFiles = state.availableZipFiles.map { it.name },
                    onImportFile = { index -> viewModel.startImportFromFile(state.availableZipFiles[index]) },
                    onPickFile = { filePicker.launch("application/zip") },
                    onRefresh = { viewModel.checkForImportableMedia() },
                )
            }
            is RomsetProgress.InProgress -> {
                Text(
                    text = stringResource(R.string.romset_importing),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = progress.currentFileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress.progressFraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${progress.currentIndex} / ${progress.totalCount}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            is RomsetProgress.Completed -> {
                Text(
                    text = stringResource(R.string.romset_import_completed, progress.fileCount),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.resetImportProgress() },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(text = stringResource(R.string.romset_import_again))
                }
            }
            is RomsetProgress.Error -> {
                Text(
                    text = stringResource(R.string.romset_import_error),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = progress.message,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.resetImportProgress() },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(text = stringResource(R.string.romset_import_try_again))
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.RomsetImportIdle(
    isSearching: Boolean,
    zipFiles: List<String>,
    onImportFile: (Int) -> Unit,
    onPickFile: () -> Unit,
    onRefresh: () -> Unit,
) {
    Text(
        text = stringResource(R.string.romset_import_description_long),
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(modifier = Modifier.height(16.dp))

    if (isSearching) {
        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
    } else if (zipFiles.isNotEmpty()) {
        Text(
            text = stringResource(R.string.romset_import_found_on_media),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn {
            items(zipFiles.indices.toList()) { index ->
                Text(
                    text = zipFiles[index],
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onImportFile(index) }
                        .padding(vertical = 12.dp),
                )
                Divider()
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    } else {
        Text(
            text = stringResource(R.string.romset_import_not_found),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = onRefresh,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(text = stringResource(R.string.romset_import_refresh))
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
    Button(
        onClick = onPickFile,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    ) {
        Text(text = stringResource(R.string.romset_import_pick_file))
    }
}
