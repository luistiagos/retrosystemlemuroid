package com.swordfish.lemuroid.app.mobile.feature.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.app.shared.library.PendingOperationsMonitor
import com.swordfish.lemuroid.common.coroutines.combine
import com.swordfish.lemuroid.lib.core.CoresSelection
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.HeavySystemFilter
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.common.coroutines.debounceAfterFirst
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import com.swordfish.lemuroid.app.shared.roms.DownloadRomsState
import com.swordfish.lemuroid.app.shared.roms.RomsDownloadManager
import com.swordfish.lemuroid.app.shared.roms.StreamingRomsManager
import com.swordfish.lemuroid.app.shared.roms.StreamingRomsState

@OptIn(FlowPreview::class)
class HomeViewModel(
    appContext: Context,
    retrogradeDb: RetrogradeDatabase,
    private val coresSelection: CoresSelection,
) : ViewModel() {
    companion object {
        const val CAROUSEL_MAX_ITEMS = 10
        const val DEBOUNCE_TIME = 100L
    }

    class Factory(
        val appContext: Context,
        val retrogradeDb: RetrogradeDatabase,
        val coresSelection: CoresSelection,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(appContext, retrogradeDb, coresSelection) as T
        }
    }

    data class UIState(
        val favoritesGames: List<Game> = emptyList(),
        val recentGames: List<Game> = emptyList(),
        val discoveryGames: List<Game> = emptyList(),
        val indexInProgress: Boolean = true,
        val isInitialLoadComplete: Boolean = false,
        val userScanInProgress: Boolean = false,
        val showNoNotificationPermissionCard: Boolean = false,
        val showNoMicrophonePermissionCard: Boolean = false,
        val showNoGamesCard: Boolean = false,
        val showDesmumeDeprecatedCard: Boolean = false,
        val showDownloadPromptDialog: Boolean = false,
    )

    private val microphonePermissionEnabledState = MutableStateFlow(true)
    private val notificationsPermissionEnabledState = MutableStateFlow(true)
    private val uiStates = MutableStateFlow(UIState())
    @SuppressLint("StaticFieldLeak") // appContext is the Application context, not Activity
    private val appCtx: Context = appContext
    private val romsDownloadManager = RomsDownloadManager(appContext)
    private val streamingRomsManager = StreamingRomsManager(appContext)
    private val downloadDialogDismissed = MutableStateFlow(false)
    private val excludedDbNames = HeavySystemFilter.excludedDbNames(HeavySystemFilter.deviceTier(appContext))
    // Emits the mobile network label (e.g. "4G", "5G") when WiFi is lost during an active download
    private val _mobileSwitchEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val mobileSwitchEvent: Flow<String> = _mobileSwitchEvent

    /** Flow that emits true while WiFi is available, false when it's lost. */
    private fun wifiStatusFlow(): Flow<Boolean> = callbackFlow {
        val cm = appCtx.getSystemService(ConnectivityManager::class.java)
        if (cm == null) { trySend(true); awaitClose { }; return@callbackFlow }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(true) }
            override fun onLost(network: Network) { trySend(false) }
        }
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            cb,
        )
        // emit initial WiFi state immediately
        val initial = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        trySend(initial)
        awaitClose { cm.unregisterNetworkCallback(cb) }
    }.distinctUntilChanged()

    fun getViewStates(): Flow<UIState> {
        return uiStates
    }

    // True while a library index operation is running (e.g. right after download completes).
    private val indexingFlow: Flow<Boolean> = indexingInProgress(appContext).distinctUntilChanged()

    // Combines WorkManager download state with DB emptiness and indexing status.
    // When state=Done but the ROMs directory was later deleted (noGames=true and NOT
    // currently indexing), override back to Idle so the download card/prompt reappear.
    // The !isIndexing guard prevents a false Idle during the post-download library scan,
    // which would otherwise flash the "Download ROMs" card and dialog right after a
    // successful download while the scanner is still populating the DB.
    fun getDownloadRomsState(): Flow<DownloadRomsState> = combine(
        romsDownloadManager.state,
        uiStates.map { it.showNoGamesCard }.distinctUntilChanged(),
        indexingFlow,
    ) { dlState, noGames, isIndexing ->
        if (dlState is DownloadRomsState.Done && noGames && !isIndexing && !romsDownloadManager.isDownloadStarted())
            DownloadRomsState.Idle
        else
            dlState
    }

    fun cancelDownload() {
        viewModelScope.launch {
            romsDownloadManager.cancelDownload()
        }
        // Suppress the auto-prompt dialog after cancellation so it doesn't
        // immediately reappear.
        downloadDialogDismissed.value = true
    }

    fun downloadAndExtractRoms() {
        romsDownloadManager.downloadAndExtract()
    }

    fun getStreamingRomsState(): Flow<StreamingRomsState> = streamingRomsManager.state

    fun startStreamingDownload() {
        streamingRomsManager.startDownload()
    }

    fun cancelStreamingDownload() {
        streamingRomsManager.cancelDownload()
        // Suppress the auto-prompt dialog after cancellation, just like cancelDownload() does
        // for the old download manager. Without this the dialog immediately re-shows because
        // PREF_DOWNLOAD_STARTED is cleared to false by cancelDownload().
        downloadDialogDismissed.value = true
    }

    fun pauseStreamingDownload() {
        streamingRomsManager.pauseDownload()
    }

    fun resumeStreamingDownload() {
        streamingRomsManager.resumeDownload()
    }

    private fun getMobileNetworkLabel(): String {
        val cm = appCtx.getSystemService(ConnectivityManager::class.java) ?: return "Móvel"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Móvel"
        return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            when {
                caps.linkDownstreamBandwidthKbps >= 20000 -> "5G"
                caps.linkDownstreamBandwidthKbps >= 1000 -> "4G"
                caps.linkDownstreamBandwidthKbps >= 200 -> "3G"
                else -> "2G"
            }
        } else "Móvel"
    }

    fun dismissDownloadDialog() {
        downloadDialogDismissed.value = true
    }

    fun updatePermissions(context: Context) {
        notificationsPermissionEnabledState.value = isNotificationsPermissionGranted(context)
        microphonePermissionEnabledState.value = isMicrophonePermissionGranted(context)
    }

    private fun isNotificationsPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }

        val permissionResult =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            )

        return permissionResult == PackageManager.PERMISSION_GRANTED
    }

    private fun isMicrophonePermissionGranted(context: Context): Boolean {
        val permissionResult =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            )

        return permissionResult == PackageManager.PERMISSION_GRANTED
    }

    private fun buildViewState(
        favoritesGames: List<Game>,
        recentGames: List<Game>,
        discoveryGames: List<Game>,
        indexInProgress: Boolean,
        notificationsPermissionEnabled: Boolean,
        showMicrophoneCard: Boolean,
        showDesmumeWarning: Boolean,
    ): UIState {
        val noGames = recentGames.isEmpty() && favoritesGames.isEmpty() && discoveryGames.isEmpty()

        return UIState(
            favoritesGames = favoritesGames,
            recentGames = recentGames,
            discoveryGames = discoveryGames,
            indexInProgress = indexInProgress,
            isInitialLoadComplete = true,
            showNoNotificationPermissionCard = !notificationsPermissionEnabled,
            showNoMicrophonePermissionCard = showMicrophoneCard,
            showNoGamesCard = noGames,
            showDesmumeDeprecatedCard = showDesmumeWarning,
        )
    }

    init {
        // Auto-start the streaming catalog download the first time the app opens when no
        // download has been done yet and the user hasn't dismissed it this session.
        // Also retry automatically if the previous attempt ended in Error (e.g. Worker
        // failed on a prior APK version and WorkManager cached the FAILED state).
        viewModelScope.launch {
            val initialState = streamingRomsManager.state.first()
            if ((initialState is StreamingRomsState.Idle || initialState is StreamingRomsState.Error)
                && !streamingRomsManager.isDownloadDone()
                && !downloadDialogDismissed.value
            ) {
                startStreamingDownload()
                downloadDialogDismissed.value = true
            }
        }
        // Monitor WiFi loss during an active download. When WiFi drops while wifiOnly=true,
        // pause the download immediately (before the Worker's HTTP request fails and clears
        // PREF_DOWNLOAD_STARTED) and notify the UI so it can offer to resume on mobile.
        viewModelScope.launch {
            wifiStatusFlow().collect { isWifi ->
                if (isWifi) return@collect  // WiFi available – nothing to do
                val wifiOnly = appCtx.getSharedPreferences(
                    "streaming_roms_prefs", Context.MODE_PRIVATE,
                ).getBoolean(StreamingRomsManager.PREF_WIFI_ONLY, true)
                if (!wifiOnly) return@collect
                if (!streamingRomsManager.isCurrentlyDownloading()) return@collect
                // Pause first so the UI transitions to Paused state and the Worker is
                // cancelled cleanly before any IOException has a chance to clear
                // PREF_DOWNLOAD_STARTED and make isCurrentlyDownloading() return false.
                streamingRomsManager.pauseDownload()
                _mobileSwitchEvent.emit(getMobileNetworkLabel())
            }
        }
        viewModelScope.launch {
            val uiStatesFlow =
                combine(
                    favoritesGames(retrogradeDb),
                    recentGames(retrogradeDb),
                    discoveryGames(retrogradeDb),
                    indexingInProgress(appContext).onStart { emit(false) },
                    notificationsPermissionEnabledState,
                    microphoneNotification(retrogradeDb).onStart { emit(false) },
                    desmumeWarningNotification().onStart { emit(false) },
                    ::buildViewState,
                ).combine(downloadDialogDismissed) { state, dismissed ->
                    state.copy(
                        showDownloadPromptDialog = state.showNoGamesCard
                            && !state.indexInProgress
                            && !romsDownloadManager.isDownloadStarted()
                            && !streamingRomsManager.isDownloadStarted()
                            && !dismissed,
                    )
                }.combine(userScanInProgress(appContext)) { state, userScan ->
                    state.copy(userScanInProgress = userScan)
                }

            uiStatesFlow
                .debounceAfterFirst(DEBOUNCE_TIME)
                .flowOn(Dispatchers.IO)
                .collect { state ->
                    if (state.showDownloadPromptDialog) {
                        startStreamingDownload()
                        downloadDialogDismissed.value = true
                    }
                    uiStates.value = state.copy(showDownloadPromptDialog = false)
                }
        }
    }

    private fun indexingInProgress(appContext: Context) =
        PendingOperationsMonitor(appContext).anyLibraryOperationInProgress()

    private fun userScanInProgress(appContext: Context) =
        PendingOperationsMonitor(appContext).isUserLibraryScanInProgress()

    private fun discoveryGames(retrogradeDb: RetrogradeDatabase) =
        if (excludedDbNames.isNotEmpty()) retrogradeDb.gameDao().selectFirstNotPlayedExcluding(CAROUSEL_MAX_ITEMS, excludedDbNames)
        else retrogradeDb.gameDao().selectFirstNotPlayed(CAROUSEL_MAX_ITEMS)

    private fun recentGames(retrogradeDb: RetrogradeDatabase) =
        if (excludedDbNames.isNotEmpty()) retrogradeDb.gameDao().selectFirstUnfavoriteRecentsExcluding(CAROUSEL_MAX_ITEMS, excludedDbNames)
        else retrogradeDb.gameDao().selectFirstUnfavoriteRecents(CAROUSEL_MAX_ITEMS)

    private fun favoritesGames(retrogradeDb: RetrogradeDatabase) =
        if (excludedDbNames.isNotEmpty()) retrogradeDb.gameDao().selectFirstFavoritesExcluding(CAROUSEL_MAX_ITEMS, excludedDbNames)
        else retrogradeDb.gameDao().selectFirstFavorites(CAROUSEL_MAX_ITEMS)

    private fun dsGamesCount(retrogradeDb: RetrogradeDatabase): Flow<Int> {
        return retrogradeDb.gameDao().selectSystemsWithCount()
            .map { systems ->
                systems
                    .firstOrNull { it.systemId == SystemID.NDS.dbname }
                    ?.count
                    ?: 0
            }
            .distinctUntilChanged()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun microphoneNotification(db: RetrogradeDatabase): Flow<Boolean> {
        return microphonePermissionEnabledState
            .flatMapLatest { isMicrophoneEnabled ->
                if (isMicrophoneEnabled) {
                    flowOf(false)
                } else {
                    combine(
                        coresSelection.getSelectedCores(),
                        dsGamesCount(db),
                    ) { cores, dsCount ->
                        cores.any { it.coreConfig.supportsMicrophone } &&
                            dsCount > 0
                    }
                }
                    .distinctUntilChanged()
            }
    }

    private fun desmumeWarningNotification(): Flow<Boolean> {
        return coresSelection.getSelectedCores()
            .map { cores -> cores.any { it.coreConfig.coreID == CoreID.DESMUME } }
            .distinctUntilChanged()
    }
}
