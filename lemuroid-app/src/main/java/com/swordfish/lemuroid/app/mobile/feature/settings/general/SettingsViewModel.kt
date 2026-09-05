package com.swordfish.lemuroid.app.mobile.feature.settings.general

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fredporciuncula.flow.preferences.FlowSharedPreferences
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.library.PendingOperationsMonitor
import com.swordfish.lemuroid.app.shared.settings.SettingsInteractor
import com.swordfish.lemuroid.lib.savesync.SaveSyncManager
import com.swordfish.lemuroid.lib.storage.SmartStoragePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.lib.library.catalog.CatalogRemovals
import com.swordfish.lemuroid.lib.library.catalog.ManifestQuickLoader
import com.swordfish.lemuroid.app.shared.roms.DownloadRomsState
import com.swordfish.lemuroid.app.shared.roms.RomsDownloadManager
import com.swordfish.lemuroid.app.shared.roms.StreamingRomsManager
import com.swordfish.lemuroid.app.shared.roms.StreamingRomsState

class SettingsViewModel(
    context: Context,
    private val settingsInteractor: SettingsInteractor,
    saveSyncManager: SaveSyncManager,
    sharedPreferences: FlowSharedPreferences,
) : ViewModel() {
    class Factory(
        private val context: Context,
        private val settingsInteractor: SettingsInteractor,
        private val saveSyncManager: SaveSyncManager,
        private val sharedPreferences: FlowSharedPreferences,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(
                context,
                settingsInteractor,
                saveSyncManager,
                sharedPreferences,
            ) as T
        }
    }

    data class State(
        val currentDirectory: String = "",
        val isSaveSyncSupported: Boolean = false,
        val smartStorageVolumes: List<SmartStoragePicker.VolumeInfo> = emptyList(),
        val smartStorageUsingRemovable: Boolean = false,
        val smartStorageUserOverride: Boolean = false,
        val defaultRomsDirPath: String = "",
    )

    private val appContext = context.applicationContext

    val indexingInProgress = PendingOperationsMonitor(context).anyLibraryOperationInProgress()

    val directoryScanInProgress = PendingOperationsMonitor(context).isDirectoryScanInProgress()

    private val romsDownloadManager = RomsDownloadManager(context.applicationContext)
    val downloadRomsState: Flow<DownloadRomsState> = romsDownloadManager.state

    private val streamingRomsManager = StreamingRomsManager(context.applicationContext, autoRestart = false)
    val streamingRomsState: Flow<StreamingRomsState> = streamingRomsManager.state

    val uiState =
        sharedPreferences.getString(context.getString(com.swordfish.lemuroid.lib.R.string.pref_key_extenral_folder))
            .asFlow()
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.Lazily, "")
            .map { selectedFolder ->
                val volumes = SmartStoragePicker.getVolumeInfoList(context)
                val usingRemovable = SmartStoragePicker.isUsingRemovableStorage(context)
                val userOverride = !selectedFolder.isNullOrEmpty()
                val defaultRomsDir = com.swordfish.lemuroid.lib.storage.DirectoriesManager(context)
                    .getInternalRomsDirectory()
                State(
                    currentDirectory = selectedFolder ?: "",
                    isSaveSyncSupported = saveSyncManager.isSupported(),
                    smartStorageVolumes = volumes,
                    smartStorageUsingRemovable = usingRemovable,
                    smartStorageUserOverride = userOverride,
                    defaultRomsDirPath = defaultRomsDir.absolutePath,
                )
            }

    /** How many catalog games the user removed — 0 hides the restore action's counter. */
    val catalogRemovalCount: StateFlow<Int> =
        flow { emitAll(CatalogRemovals.observe(appContext)) }
            .map { it.size }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    private val _catalogResetInProgress = MutableStateFlow(false)
    val catalogResetInProgress: StateFlow<Boolean> = _catalogResetInProgress.asStateFlow()

    private val _catalogResetCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val catalogResetCompleted: SharedFlow<Unit> = _catalogResetCompleted.asSharedFlow()

    /**
     * Brings back every game the user removed from the catalog: clears the removal list and
     * forces a full manifest pass, which re-inserts the missing rows. Downloaded ROMs deleted
     * along the way are not restored — the games come back as placeholders, ready to download.
     */
    fun resetCatalog() {
        if (_catalogResetInProgress.value) return
        viewModelScope.launch {
            _catalogResetInProgress.value = true
            try {
                withContext(Dispatchers.IO) {
                    CatalogRemovals.clear(appContext)
                    ManifestQuickLoader.forceReload(appContext)
                }
                LibraryIndexScheduler.triggerCatalogQuickLoad(appContext)
            } finally {
                _catalogResetInProgress.value = false
                _catalogResetCompleted.tryEmit(Unit)
            }
        }
    }

    fun changeLocalStorageFolder() {
        settingsInteractor.changeLocalStorageFolder()
    }

    fun downloadAndExtractRoms() {
        romsDownloadManager.downloadAndExtract()
    }

    /** Deletes all streaming-downloaded ROMs and restarts the download from scratch. */
    fun redownloadStreamingRoms() {
        viewModelScope.launch {
            streamingRomsManager.resetForRedownload()
            streamingRomsManager.startDownload()
        }
    }
}
