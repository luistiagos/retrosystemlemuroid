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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
