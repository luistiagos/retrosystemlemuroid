package com.swordfish.lemuroid.app.mobile.feature.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidSmallGameImage
import com.swordfish.lemuroid.app.shared.roms.RomOnDemandManager
import com.swordfish.lemuroid.lib.library.db.entity.Game

/**
 * Dialog shown when the user taps a catalog placeholder (0-byte) game.
 *
 * States:
 * - **Idle**: Game image + title + confirm message + Download / Cancel buttons.
 * - **Downloading**: Image + title + progress bar + Pause / Cancel buttons.
 * - **Paused**: Image + title + progress bar + Resume / Cancel buttons.
 * - **Error / NotFound**: Image + title + error message + Close button.
 *
 * On success, [onDownloadComplete] is called so the caller can launch the game.
 */
@Composable
fun RomDownloadDialog(
    selectedGame: Game,
    selectedGameState: MutableState<Game?>,
    romOnDemandManager: RomOnDemandManager,
    onDownloadComplete: (Game) -> Unit,
) {
    var downloadState by remember(selectedGame) { mutableStateOf<DownloadState>(DownloadState.Idle) }
    var progress by remember(selectedGame) { mutableFloatStateOf(0f) }
    val isPaused by romOnDemandManager.isPaused.collectAsState()

    // Kick off the actual download when the user confirms.
    LaunchedEffect(downloadState) {
        if (downloadState !is DownloadState.Downloading) return@LaunchedEffect

        val result = romOnDemandManager.downloadRom(selectedGame) { p ->
            progress = p
        }

        downloadState = when (result) {
            is RomOnDemandManager.DownloadResult.Success -> DownloadState.Done
            is RomOnDemandManager.DownloadResult.NotFound -> DownloadState.NotFound
            is RomOnDemandManager.DownloadResult.Failure -> DownloadState.Error(result.message)
        }
    }

    // Auto-launch game on successful download.
    LaunchedEffect(downloadState) {
        if (downloadState is DownloadState.Done) {
            selectedGameState.value = null
            onDownloadComplete(selectedGame)
        }
    }

    val isDownloading = downloadState is DownloadState.Downloading

    AlertDialog(
        // Only dismissible while idle or in terminal states (not during active download/pause).
        onDismissRequest = {
            if (!isDownloading) selectedGameState.value = null
        },
        title = {
            Text(stringResource(R.string.rom_download_dialog_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Game cover image + title â€” shown in every state.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.size(72.dp)) {
                        LemuroidSmallGameImage(game = selectedGame)
                    }
                    Text(
                        text = selectedGame.title,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                }

                when (val state = downloadState) {
                    is DownloadState.Idle -> {
                        Text(
                            text = stringResource(R.string.rom_download_dialog_message),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    is DownloadState.Downloading -> {
                        val statusText = if (isPaused)
                            stringResource(R.string.rom_download_dialog_paused)
                        else
                            stringResource(
                                R.string.rom_download_dialog_downloading,
                                (progress * 100).toInt(),
                            )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        if (progress > 0f) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }

                    is DownloadState.NotFound -> {
                        Text(text = stringResource(R.string.rom_download_dialog_not_found))
                    }

                    is DownloadState.Error -> {
                        Text(
                            text = stringResource(R.string.rom_download_dialog_error, state.message),
                        )
                    }

                    is DownloadState.Done -> { /* handled by LaunchedEffect above */ }
                }
            }
        },
        confirmButton = {
            when {
                downloadState is DownloadState.Idle -> {
                    TextButton(onClick = { downloadState = DownloadState.Downloading }) {
                        Text(stringResource(R.string.rom_download_dialog_confirm))
                    }
                }
                isDownloading && isPaused -> {
                    // Paused â†’ show Resume as the primary action.
                    TextButton(onClick = { romOnDemandManager.resumeDownload() }) {
                        Text(stringResource(R.string.rom_download_dialog_resume))
                    }
                }
                isDownloading -> {
                    // Downloading â†’ show Pause as the primary action.
                    TextButton(onClick = { romOnDemandManager.pauseDownload() }) {
                        Text(stringResource(R.string.rom_download_dialog_pause))
                    }
                }
                downloadState is DownloadState.NotFound || downloadState is DownloadState.Error -> {
                    TextButton(onClick = { selectedGameState.value = null }) {
                        Text(stringResource(R.string.rom_download_dialog_cancel))
                    }
                }
                else -> {}
            }
        },
        dismissButton = {
            when {
                downloadState is DownloadState.Idle -> {
                    TextButton(onClick = { selectedGameState.value = null }) {
                        Text(stringResource(R.string.rom_download_dialog_cancel))
                    }
                }
                isDownloading -> {
                    // Cancel during download / pause: interrupt OkHttp call and dismiss.
                    TextButton(onClick = {
                        romOnDemandManager.cancelActiveDownload()
                        selectedGameState.value = null
                    }) {
                        Text(stringResource(R.string.rom_download_dialog_cancel))
                    }
                }
                else -> {}
            }
        },
    )
}

private sealed class DownloadState {
    object Idle : DownloadState()
    object Downloading : DownloadState()
    object Done : DownloadState()
    object NotFound : DownloadState()
    data class Error(val message: String) : DownloadState()
}
