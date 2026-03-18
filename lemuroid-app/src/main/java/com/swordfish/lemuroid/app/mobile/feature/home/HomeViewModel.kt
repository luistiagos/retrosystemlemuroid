package com.swordfish.lemuroid.app.mobile.feature.home

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.app.shared.library.PendingOperationsMonitor
import com.swordfish.lemuroid.app.shared.settings.StorageFrameworkPickerLauncher
import com.swordfish.lemuroid.common.coroutines.combine
import com.swordfish.lemuroid.lib.core.CoresSelection
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.swordfish.lemuroid.app.shared.roms.DownloadRomsState
import com.swordfish.lemuroid.app.shared.roms.RomsDownloadManager

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
        val showNoNotificationPermissionCard: Boolean = false,
        val showNoMicrophonePermissionCard: Boolean = false,
        val showNoGamesCard: Boolean = false,
        val showDesmumeDeprecatedCard: Boolean = false,
        val showDirectoryInaccessibleCard: Boolean = false,
        val showDownloadPromptDialog: Boolean = false,
    )

    private val microphonePermissionEnabledState = MutableStateFlow(true)
    private val notificationsPermissionEnabledState = MutableStateFlow(true)
    private val uiStates = MutableStateFlow(UIState())
    private val appCtx: Context = appContext
    private val romsDownloadManager = RomsDownloadManager(appContext)
    private val downloadDialogDismissed = MutableStateFlow(false)

    fun getViewStates(): Flow<UIState> {
        return uiStates
    }

    fun changeLocalStorageFolder(context: Context) {
        StorageFrameworkPickerLauncher.pickFolder(context)
    }

    // True when the DB has no games at all.
    private val noGamesFlow: Flow<Boolean> = combine(
        favoritesGames(retrogradeDb),
        recentGames(retrogradeDb),
        discoveryGames(retrogradeDb),
    ) { fav, rec, disc -> fav.isEmpty() && rec.isEmpty() && disc.isEmpty() }
        .distinctUntilChanged()

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
        noGamesFlow,
        indexingFlow,
    ) { dlState, noGames, isIndexing ->
        if (dlState is DownloadRomsState.Done && noGames && !isIndexing && !romsDownloadManager.isDownloadStarted())
            DownloadRomsState.Idle
        else
            dlState
    }

    @Suppress("DEPRECATION")
    fun getCurrentDirectoryFlow(): Flow<String> = callbackFlow {
        val prefs = SharedPreferencesHelper.getLegacySharedPreferences(appCtx)
        val key = appCtx.getString(com.swordfish.lemuroid.lib.R.string.pref_key_extenral_folder)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == key) trySend(prefs.getString(key, "") ?: "")
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(prefs.getString(key, "") ?: "")
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun cancelDownload() {
        // cancelDownload() deletes ~5 GB of files — must run on IO dispatcher
        // to avoid blocking the main thread and triggering an ANR.
        viewModelScope.launch(Dispatchers.IO) {
            romsDownloadManager.cancelDownload()
        }
        // Suppress the auto-prompt dialog after cancellation so it doesn't
        // immediately reappear.
        downloadDialogDismissed.value = true
    }

    fun downloadAndExtractRoms() {
        romsDownloadManager.downloadAndExtract(viewModelScope)
    }

    fun dismissDownloadDialog() {
        downloadDialogDismissed.value = true
    }

    @Suppress("DEPRECATION")
    private fun getDirectoryAccessibilityFlow(): Flow<Boolean> = callbackFlow {
        val prefKey = appCtx.getString(com.swordfish.lemuroid.lib.R.string.pref_key_extenral_folder)
        val prefs = SharedPreferencesHelper.getLegacySharedPreferences(appCtx)

        suspend fun checkAndSend() {
            val uri = prefs.getString(prefKey, "") ?: ""
            if (uri.isEmpty()) { trySend(false); return }
            val accessible = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching {
                    DocumentFile.fromTreeUri(appCtx, Uri.parse(uri))?.canRead() == true
                }.getOrElse { false }
            }
            trySend(!accessible)
        }

        // Emit false immediately so combine doesn't block startup waiting for IO
        trySend(false)

        // Check actual accessibility in background without blocking the flow
        launch { checkAndSend() }

        val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == prefKey) launch { checkAndSend() }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        val mediaReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { launch { checkAndSend() } }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(appCtx, mediaReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
            appCtx.unregisterReceiver(mediaReceiver)
        }
    }.distinctUntilChanged()

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
        directoryInaccessible: Boolean,
    ): UIState {
        val noGames = recentGames.isEmpty() && favoritesGames.isEmpty() && discoveryGames.isEmpty()

        return UIState(
            favoritesGames = favoritesGames,
            recentGames = recentGames,
            discoveryGames = discoveryGames,
            indexInProgress = indexInProgress,
            showNoNotificationPermissionCard = !notificationsPermissionEnabled,
            showNoMicrophonePermissionCard = showMicrophoneCard,
            showNoGamesCard = noGames,
            showDesmumeDeprecatedCard = showDesmumeWarning,
            showDirectoryInaccessibleCard = directoryInaccessible,
        )
    }

    init {
        viewModelScope.launch {
            val uiStatesFlow =
                combine(
                    favoritesGames(retrogradeDb),
                    recentGames(retrogradeDb),
                    discoveryGames(retrogradeDb),
                    indexingInProgress(appContext),
                    notificationsPermissionEnabledState,
                    microphoneNotification(retrogradeDb),
                    desmumeWarningNotification(),
                    getDirectoryAccessibilityFlow(),
                    ::buildViewState,
                ).combine(downloadDialogDismissed) { state, dismissed ->
                    // Show dialog when there are no games, no indexing is running, and no
                    // download is in progress. The !indexInProgress guard is critical: right
                    // after a successful download the library scan runs and the DB is
                    // temporarily empty — without this guard the dialog would flash.
                    // We intentionally omit isDownloadDone(): if ROMs are later deleted and
                    // the DB empties again (with indexInProgress=false), the dialog correctly
                    // reappears without being blocked by the stale PREF_DOWNLOAD_DONE flag.
                    state.copy(
                        showDownloadPromptDialog = state.showNoGamesCard
                            && !state.indexInProgress
                            && !romsDownloadManager.isDownloadStarted()
                            && !dismissed,
                    )
                }

            uiStatesFlow
                .debounce(DEBOUNCE_TIME)
                .flowOn(Dispatchers.IO)
                .collect { uiStates.value = it }
        }
    }

    private fun indexingInProgress(appContext: Context) =
        PendingOperationsMonitor(appContext).anyLibraryOperationInProgress()

    private fun discoveryGames(retrogradeDb: RetrogradeDatabase) =
        retrogradeDb.gameDao().selectFirstNotPlayed(CAROUSEL_MAX_ITEMS)

    private fun recentGames(retrogradeDb: RetrogradeDatabase) =
        retrogradeDb.gameDao().selectFirstUnfavoriteRecents(CAROUSEL_MAX_ITEMS)

    private fun favoritesGames(retrogradeDb: RetrogradeDatabase) =
        retrogradeDb.gameDao().selectFirstFavorites(CAROUSEL_MAX_ITEMS)

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
