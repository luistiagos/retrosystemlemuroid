package com.swordfish.lemuroid.app.mobile.feature.settings.romset

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.lib.romset.RomsetProgress

@Composable
fun RomsetExportScreen(
    modifier: Modifier = Modifier,
    viewModel: RomsetViewModel,
) {
    val context = LocalContext.current
    val state = viewModel.uiState.collectAsState().value

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            viewModel.updateDestinationFreeSpace(uri)
            viewModel.startExportToUri(uri)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        when (val progress = state.exportProgress) {
            is RomsetProgress.Idle -> {
                RomsetExportIdle(
                    exportSizeBytes = state.exportSizeBytes,
                    onExport = { folderPicker.launch(null) },
                )
            }
            is RomsetProgress.InProgress -> {
                Text(
                    text = stringResource(R.string.romset_exporting),
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
                    text = stringResource(R.string.romset_export_completed, progress.fileCount),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.resetExportProgress() },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(text = stringResource(R.string.romset_export_again))
                }
            }
            is RomsetProgress.Error -> {
                Text(
                    text = stringResource(R.string.romset_export_error),
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
                    onClick = { viewModel.resetExportProgress() },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(text = stringResource(R.string.romset_export_try_again))
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.RomsetExportIdle(
    exportSizeBytes: Long,
    onExport: () -> Unit,
) {
    val context = LocalContext.current

    Text(
        text = stringResource(R.string.romset_export_description_long),
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(modifier = Modifier.height(16.dp))
    if (exportSizeBytes > 0) {
        Text(
            text = stringResource(
                R.string.romset_export_size,
                Formatter.formatFileSize(context, exportSizeBytes),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
    Button(
        onClick = onExport,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    ) {
        Text(text = stringResource(R.string.romset_export_button))
    }
}
