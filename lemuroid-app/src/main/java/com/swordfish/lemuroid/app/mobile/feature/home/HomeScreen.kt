package com.swordfish.lemuroid.app.mobile.feature.home

import android.Manifest
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidGameCard
import com.swordfish.lemuroid.app.utils.android.ComposableLifecycle
import com.swordfish.lemuroid.common.displayDetailsSettingsScreen
import com.swordfish.lemuroid.app.shared.roms.DownloadRomsState
import com.swordfish.lemuroid.app.shared.roms.StreamingRomsManager
import com.swordfish.lemuroid.app.shared.roms.StreamingRomsState
import com.swordfish.lemuroid.lib.library.db.entity.Game

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel,
    downloadedFileNames: Set<String> = emptySet(),
    onGameClick: (Game) -> Unit,
    onGameLongClick: (Game) -> Unit,
    onOpenCoreSelection: () -> Unit,
) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext

    ComposableLifecycle { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                viewModel.updatePermissions(applicationContext)
            }
            else -> { }
        }
    }

    val permissionsLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted: Boolean ->
            if (!isGranted) {
                context.displayDetailsSettingsScreen()
            }
        }

    val state = viewModel.getViewStates().collectAsState(HomeViewModel.UIState())
    val downloadRomsState = viewModel.getDownloadRomsState().collectAsState(DownloadRomsState.Idle)
    val streamingRomsState = viewModel.getStreamingRomsState().collectAsState(StreamingRomsState.Idle)

    // T5: log when game data is first visible in the UI (composable recomposition)
    val hasGames = state.value.recentGames.isNotEmpty() ||
        state.value.favoritesGames.isNotEmpty() ||
        state.value.discoveryGames.isNotEmpty()
    if (hasGames) {
        SideEffect {
            android.util.Log.d("PERF", "T5_HOMESCREEN_GAMES_VISIBLE recent=${state.value.recentGames.size} favs=${state.value.favoritesGames.size} discovery=${state.value.discoveryGames.size}")
        }
    }

    // Collect network-switched-to-mobile events emitted by HomeViewModel
    var showMobileSwitchDialog by remember { mutableStateOf(false) }
    var mobileSwitchLabel by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        viewModel.mobileSwitchEvent.collect { label ->
            mobileSwitchLabel = label
            showMobileSwitchDialog = true
        }
    }
    if (showMobileSwitchDialog) {
        AlertDialog(
            onDismissRequest = { showMobileSwitchDialog = false },
            title = { Text(stringResource(R.string.home_streaming_mobile_title, mobileSwitchLabel)) },
            text = { Text(stringResource(R.string.home_streaming_mobile_message, mobileSwitchLabel)) },
            confirmButton = {
                // Download was already paused when WiFi dropped; "Continuar" resumes it on mobile.
                TextButton(onClick = {
                    showMobileSwitchDialog = false
                    viewModel.resumeStreamingDownload()
                }) {
                    Text(stringResource(R.string.home_streaming_mobile_continue))
                }
            },
            dismissButton = {
                // Download is already paused; just close the dialog.
                TextButton(onClick = { showMobileSwitchDialog = false }) {
                    Text(stringResource(R.string.home_streaming_mobile_pause))
                }
            },
        )
    }

    HomeScreen(
        modifier,
        state.value,
        downloadRomsState.value,
        streamingRomsState.value,
        downloadedFileNames,
        onGameClick,
        onGameLongClick,
        onOpenCoreSelection,
        {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return@HomeScreen
            }

            permissionsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        { permissionsLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        { viewModel.downloadAndExtractRoms() },
        { viewModel.dismissDownloadDialog() },
        { viewModel.cancelDownload() },
        { viewModel.startStreamingDownload() },
        { viewModel.cancelStreamingDownload() },
        { viewModel.pauseStreamingDownload() },
        { viewModel.resumeStreamingDownload() },
    ) // TODO COMPOSE We need to understand what's going to happen here.
}

@Composable
private fun HomeScreen(
    modifier: Modifier = Modifier,
    state: HomeViewModel.UIState,
    downloadRomsState: DownloadRomsState,
    streamingRomsState: StreamingRomsState,
    downloadedFileNames: Set<String> = emptySet(),
    onGameClicked: (Game) -> Unit,
    onGameLongClick: (Game) -> Unit,
    onOpenCoreSelection: () -> Unit,
    onEnableNotificationsClicked: () -> Unit,
    onEnableMicrophoneClicked: () -> Unit,
    onDownloadRomsClicked: () -> Unit,
    onDismissDownloadDialog: () -> Unit,
    onCancelDownloadClicked: () -> Unit,
    onStartStreamingClicked: () -> Unit,
    onCancelStreamingClicked: () -> Unit,
    onPauseStreamingClicked: () -> Unit,
    onResumeStreamingClicked: () -> Unit,
) {
    if (state.showDownloadPromptDialog) {
        AlertDialog(
            onDismissRequest = onDismissDownloadDialog,
            title = { Text(stringResource(R.string.home_download_dialog_title)) },
            text = { Text(stringResource(R.string.home_download_dialog_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onDismissDownloadDialog()
                    onStartStreamingClicked()
                }) {
                    Text(stringResource(R.string.home_download_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDownloadDialog) {
                    Text(stringResource(R.string.home_download_dialog_cancel))
                }
            },
        )
    }
    if (!state.isInitialLoadComplete) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val sectionRecent = stringResource(id = R.string.recent)
    val sectionFavorites = stringResource(id = R.string.favorites)
    val sectionDiscover = stringResource(id = R.string.discover)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AnimatedVisibility(state.userScanInProgress) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                AnimatedVisibility(state.showNoNotificationPermissionCard) {
                    HomeNotification(
                        titleId = R.string.home_notification_title,
                        messageId = R.string.home_notification_message,
                        actionId = R.string.home_notification_action,
                        onAction = onEnableNotificationsClicked,
                    )
                }
                AnimatedVisibility(state.showNoMicrophonePermissionCard) {
                    HomeNotification(
                        titleId = R.string.home_microphone_title,
                        messageId = R.string.home_microphone_message,
                        actionId = R.string.home_microphone_action,
                        onAction = onEnableMicrophoneClicked,
                    )
                }
                AnimatedVisibility(state.showDesmumeDeprecatedCard) {
                    HomeNotification(
                        titleId = R.string.home_notification_desmume_deprecated_title,
                        messageId = R.string.home_notification_desmume_deprecated_message,
                        actionId = R.string.home_notification_desmume_deprecated_action,
                        onAction = onOpenCoreSelection,
                    )
                }
                // Card is only shown while the download is not yet complete.
                // The Done state is handled exclusively via Settings → "Baixar novamente",
                // which calls resetForRedownload() and transitions the state back to Idle.
                AnimatedVisibility(streamingRomsState !is StreamingRomsState.Done) {
                    HomeStreamingCard(
                        state = streamingRomsState,
                        onDownloadClicked = onStartStreamingClicked,
                        onCancelClicked = onCancelStreamingClicked,
                        onPauseClicked = onPauseStreamingClicked,
                        onResumeClicked = onResumeStreamingClicked,
                    )
                }
            }
        }

        homeGridSection(
            sectionRecent,
            state.recentGames,
            downloadedFileNames,
            onGameClicked,
            onGameLongClick,
        )
        homeGridSection(
            sectionFavorites,
            state.favoritesGames,
            downloadedFileNames,
            onGameClicked,
            onGameLongClick,
        )
        homeGridSection(
            sectionDiscover,
            state.discoveryGames,
            downloadedFileNames,
            onGameClicked,
            onGameLongClick,
        )
    }
}

private fun LazyGridScope.homeGridSection(
    title: String,
    games: List<Game>,
    downloadedFileNames: Set<String>,
    onGameClicked: (Game) -> Unit,
    onGameLongClick: (Game) -> Unit,
) {
    if (games.isEmpty()) return

    item(span = { GridItemSpan(maxLineSpan) }) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }

    items(games, key = { it.id }) { game ->
        LemuroidGameCard(
            modifier = Modifier.fillMaxWidth(),
            game = game,
            isDownloaded = downloadedFileNames.contains(game.fileName),
            onClick = { onGameClicked(game) },
            onLongClick = { onGameLongClick(game) },
        )
    }
}

@Composable
private fun HomeNotification(
    titleId: Int,
    messageId: Int,
    actionId: Int,
    enabled: Boolean = true,
    onAction: () -> Unit = { },
    extraContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    ElevatedCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(titleId),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(messageId),
                style = MaterialTheme.typography.bodyMedium,
            )
            extraContent?.invoke(this)
            OutlinedButton(
                modifier = Modifier.align(Alignment.End),
                onClick = onAction,
                enabled = enabled,
            ) {
                Text(stringResource(id = actionId))
            }
        }
    }
}

@Composable
private fun HomeDownloadCard(
    state: DownloadRomsState,
    onDownloadClicked: () -> Unit,
    onCancelClicked: () -> Unit,
) {
    var showCancelDialog by remember { mutableStateOf(false) }
    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text(stringResource(R.string.home_download_cancel_dialog_title)) },
            text = { Text(stringResource(R.string.home_download_cancel_dialog_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showCancelDialog = false
                    onCancelClicked()
                }) {
                    Text(stringResource(R.string.home_download_cancel_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text(stringResource(R.string.home_download_cancel_dialog_dismiss))
                }
            },
        )
    }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.home_download_roms_title),
                style = MaterialTheme.typography.titleMedium,
            )
            when (state) {
                is DownloadRomsState.Idle -> {
                    Text(
                        text = stringResource(R.string.home_download_roms_message),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        modifier = Modifier.align(Alignment.End),
                        onClick = onDownloadClicked,
                    ) {
                        Text(stringResource(R.string.home_download_roms_action))
                    }
                }
                is DownloadRomsState.Downloading -> {
                    Text(
                        text = stringResource(R.string.home_download_roms_downloading, (state.progress * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        modifier = Modifier.align(Alignment.End),
                        onClick = { showCancelDialog = true },
                    ) {
                        Text(stringResource(R.string.home_download_roms_cancel))
                    }
                }
                is DownloadRomsState.Extracting -> {
                    Text(
                        text = stringResource(R.string.home_download_roms_extracting, (state.progress * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        modifier = Modifier.align(Alignment.End),
                        onClick = { showCancelDialog = true },
                    ) {
                        Text(stringResource(R.string.home_download_roms_cancel))
                    }
                }
                is DownloadRomsState.Done -> {
                    Text(
                        text = stringResource(R.string.home_download_roms_done),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        modifier = Modifier.align(Alignment.End),
                        onClick = onDownloadClicked,
                    ) {
                        Text(stringResource(R.string.home_download_roms_action_again))
                    }
                }
                is DownloadRomsState.Error -> {
                    Text(
                        text = stringResource(R.string.home_download_roms_error, state.message),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        modifier = Modifier.align(Alignment.End),
                        onClick = onDownloadClicked,
                    ) {
                        Text(stringResource(R.string.home_download_roms_action_retry))
                    }
                }
            }
        }
    }
}

/**
 * Returns true when the WiFi-only preference is enabled and there is no active WiFi connection.
 * In that case the caller should prompt the user before starting/resuming a download.
 */
private fun isWifiNeeded(context: android.content.Context): Boolean {
    val streamingPrefs = context.getSharedPreferences(
        "streaming_roms_prefs", android.content.Context.MODE_PRIVATE
    )
    val wifiOnly = streamingPrefs.getBoolean(StreamingRomsManager.PREF_WIFI_ONLY, true)
    if (!wifiOnly) return false
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val isOnWifi = cm?.getNetworkCapabilities(cm.activeNetwork)
        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    return !isOnWifi
}

private fun isNetworkAvailable(context: android.content.Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

@Composable
private fun HomeStreamingCard(
    state: StreamingRomsState,
    onDownloadClicked: () -> Unit,
    onCancelClicked: () -> Unit,
    onPauseClicked: () -> Unit,
    onResumeClicked: () -> Unit,
) {
    val context = LocalContext.current
    var showCancelDialog by remember { mutableStateOf(false) }
    var showNoNetworkDialog by remember { mutableStateOf(false) }
    var wifiConfirmAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text(stringResource(R.string.home_streaming_cancel_title)) },
            text = { Text(stringResource(R.string.home_streaming_cancel_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showCancelDialog = false
                    onCancelClicked()
                }) {
                    Text(stringResource(R.string.home_streaming_cancel_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text(stringResource(R.string.home_streaming_cancel_dismiss))
                }
            },
        )
    }
    if (showNoNetworkDialog) {
        AlertDialog(
            onDismissRequest = { showNoNetworkDialog = false },
            title = { Text(stringResource(R.string.home_streaming_no_network_title)) },
            text = { Text(stringResource(R.string.home_streaming_no_network_message)) },
            confirmButton = {
                TextButton(onClick = { showNoNetworkDialog = false }) {
                    Text(stringResource(R.string.home_streaming_no_network_ok))
                }
            },
        )
    }
    if (wifiConfirmAction != null) {
        AlertDialog(
            onDismissRequest = { wifiConfirmAction = null },
            title = { Text(stringResource(R.string.home_streaming_nowifi_title)) },
            text = { Text(stringResource(R.string.home_streaming_nowifi_message)) },
            confirmButton = {
                TextButton(onClick = {
                    wifiConfirmAction?.invoke()
                    wifiConfirmAction = null
                }) {
                    Text(stringResource(R.string.home_streaming_nowifi_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { wifiConfirmAction = null }) {
                    Text(stringResource(R.string.home_streaming_nowifi_cancel))
                }
            },
        )
    }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.home_streaming_roms_title),
                style = MaterialTheme.typography.titleMedium,
            )
            when (state) {
                is StreamingRomsState.Idle -> {
                    Text(
                        text = stringResource(R.string.home_streaming_roms_message),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        modifier = Modifier.align(Alignment.End),
                        onClick = {
                            if (!isNetworkAvailable(context)) showNoNetworkDialog = true
                            else if (isWifiNeeded(context)) wifiConfirmAction = { onDownloadClicked() }
                            else onDownloadClicked()
                        },
                    ) {
                        Text(stringResource(R.string.home_streaming_roms_action))
                    }
                }
                is StreamingRomsState.Downloading -> {
                    val fileLabel = if (state.currentFile.isNotEmpty()) state.currentFile else "…"
                    val countLabel = if (state.totalFiles > 0)
                        "${state.downloadedFiles}/${state.totalFiles}"
                    else
                        "${state.downloadedFiles}"
                    Text(
                        text = stringResource(
                            R.string.home_streaming_roms_downloading,
                            (state.progress * 100).toInt(),
                            countLabel,
                            fileLabel,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = { showCancelDialog = true }) {
                            Text(stringResource(R.string.home_streaming_roms_action_cancel))
                        }
                        OutlinedButton(onClick = onPauseClicked) {
                            Text(stringResource(R.string.home_streaming_roms_pause))
                        }
                    }
                }
                is StreamingRomsState.Paused -> {
                    Text(
                        text = stringResource(R.string.home_streaming_roms_paused),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = { showCancelDialog = true }) {
                            Text(stringResource(R.string.home_streaming_roms_action_cancel))
                        }
                        OutlinedButton(onClick = {
                            if (!isNetworkAvailable(context)) showNoNetworkDialog = true
                            else if (isWifiNeeded(context)) wifiConfirmAction = { onResumeClicked() }
                            else onResumeClicked()
                        }) {
                            Text(stringResource(R.string.home_streaming_roms_resume))
                        }
                    }
                }
                is StreamingRomsState.Error -> {
                    Text(
                        text = stringResource(R.string.home_download_roms_error, state.message),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        modifier = Modifier.align(Alignment.End),
                        onClick = onDownloadClicked,
                    ) {
                        Text(stringResource(R.string.home_download_roms_action_retry))
                    }
                }
                // Card is hidden by AnimatedVisibility when Done; no UI needed here.
                is StreamingRomsState.Done -> {}
            }
        }
    }
}
