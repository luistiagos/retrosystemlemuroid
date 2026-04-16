package com.swordfish.lemuroid.app.mobile.feature.settings.transfer

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.transfer.GameExportManager
import com.swordfish.lemuroid.lib.transfer.GameImportManager
import com.swordfish.lemuroid.lib.transfer.TransferGameEntry
import com.swordfish.lemuroid.lib.transfer.TransferManifest
import com.swordfish.lemuroid.lib.transfer.TransferProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TransferViewModel(
    private val context: Context,
    private val retrogradeDatabase: RetrogradeDatabase,
    private val exportManager: GameExportManager,
    private val importManager: GameImportManager,
) : ViewModel() {

    data class UiState(
        val allGames: List<Game> = emptyList(),
        val selectedGameIds: Set<Int> = emptySet(),
        val includeApk: Boolean = true,
        val exportProgress: TransferProgress = TransferProgress.Idle,
        val importProgress: TransferProgress = TransferProgress.Idle,
        val exportSizeBytes: Long = 0L,
        val destinationFreeBytes: Long = 0L,
        val importManifest: TransferManifest? = null,
        val importManifestFile: File? = null,
        val isLoading: Boolean = true,
    )

    private val _allGames = MutableStateFlow<List<Game>>(emptyList())
    private val _selectedGameIds = MutableStateFlow<Set<Int>>(emptySet())
    private val _includeApk = MutableStateFlow(true)
    private val _exportSizeBytes = MutableStateFlow(0L)
    private val _destinationFreeBytes = MutableStateFlow(0L)
    private val _importManifest = MutableStateFlow<TransferManifest?>(null)
    private val _importManifestFile = MutableStateFlow<File?>(null)
    private val _isLoading = MutableStateFlow(true)

    val uiState: StateFlow<UiState> = combine(
        _allGames,
        _selectedGameIds,
        _includeApk,
        exportManager.progress,
        importManager.progress,
    ) { games, selected, includeApk, exportProgress, importProgress ->
        UiState(
            allGames = games,
            selectedGameIds = selected,
            includeApk = includeApk,
            exportProgress = exportProgress,
            importProgress = importProgress,
            exportSizeBytes = _exportSizeBytes.value,
            destinationFreeBytes = _destinationFreeBytes.value,
            importManifest = _importManifest.value,
            importManifestFile = _importManifestFile.value,
            isLoading = _isLoading.value,
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, UiState())

    init {
        loadGames()
    }

    private fun loadGames() {
        viewModelScope.launch {
            _isLoading.value = true
            val games = withContext(Dispatchers.IO) {
                // downloadedRomDao is the authoritative source for on-demand downloaded games.
                // We also accept file:// URIs with actual content (locally added ROMs)
                // and any content:// SAF URI (user-managed storage).
                val downloadedFileNames = retrogradeDatabase.downloadedRomDao()
                    .getAllDownloadedFileNames().toSet()
                retrogradeDatabase.gameDao().selectAll()
                    .filter { game ->
                        if (game.fileName in downloadedFileNames) return@filter true
                        val uri = Uri.parse(game.fileUri)
                        when (uri.scheme) {
                            "file" -> uri.path?.let {
                                val f = File(it)
                                f.exists() && f.length() > 0
                            } == true
                            else -> true
                        }
                    }
            }
            _allGames.value = games
            _selectedGameIds.value = games.map { it.id }.toSet()
            recalculateSize()
            _isLoading.value = false
        }
    }

    fun toggleGameSelection(gameId: Int) {
        val current = _selectedGameIds.value.toMutableSet()
        if (current.contains(gameId)) {
            current.remove(gameId)
        } else {
            current.add(gameId)
        }
        _selectedGameIds.value = current
        recalculateSize()
    }

    fun selectAllGames() {
        _selectedGameIds.value = _allGames.value.map { it.id }.toSet()
        recalculateSize()
    }

    fun deselectAllGames() {
        _selectedGameIds.value = emptySet()
        recalculateSize()
    }

    fun setIncludeApk(include: Boolean) {
        _includeApk.value = include
        recalculateSize()
    }

    private fun recalculateSize() {
        viewModelScope.launch(Dispatchers.IO) {
            val selectedGames = _allGames.value.filter { it.id in _selectedGameIds.value }
            _exportSizeBytes.value = exportManager.calculateExportSize(selectedGames, _includeApk.value)
        }
    }

    fun startExport(destDir: File) {
        viewModelScope.launch {
            val selectedGames = _allGames.value.filter { it.id in _selectedGameIds.value }
            if (selectedGames.isEmpty()) return@launch
            exportManager.export(selectedGames, destDir, _includeApk.value)
        }
    }

    fun startExportToUri(treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            // Resolve SAF tree to a writable directory
            // For simplicity we'll use the cache dir as a staging area, then copy.
            // Most SAF trees on SD cards still allow File operations via the translated path.
            val selectedGames = _allGames.value.filter { it.id in _selectedGameIds.value }
            if (selectedGames.isEmpty()) return@launch

            // Try to resolve a file path from the SAF URI
            val destDir = resolveFileFromTreeUri(treeUri)
            if (destDir != null) {
                exportManager.export(selectedGames, destDir, _includeApk.value)
            }
        }
    }

    fun checkForImportableMedia() {
        viewModelScope.launch(Dispatchers.IO) {
            val manifestFile = importManager.findManifestOnVolumes()
            if (manifestFile != null) {
                val manifest = importManager.readManifest(manifestFile)
                _importManifest.value = manifest
                _importManifestFile.value = manifestFile
            }
        }
    }

    fun startImport(selectedEntries: List<TransferGameEntry>? = null) {
        viewModelScope.launch {
            val manifestFile = _importManifestFile.value ?: return@launch
            val manifest = _importManifest.value ?: return@launch
            importManager.import(manifestFile, manifest, selectedEntries)
        }
    }

    fun updateDestinationFreeSpace(path: File) {
        viewModelScope.launch(Dispatchers.IO) {
            _destinationFreeBytes.value = runCatching {
                StatFs(path.absolutePath).availableBytes
            }.getOrDefault(0L)
        }
    }

    private fun resolveFileFromTreeUri(treeUri: Uri): File? {
        // Try common patterns to resolve SAF tree URI to a file path
        val path = treeUri.path ?: return null
        // Pattern: /tree/primary:folder -> /storage/emulated/0/folder
        // Pattern: /tree/XXXX-XXXX:folder -> /storage/XXXX-XXXX/folder
        val parts = path.substringAfter("/tree/").split(":", limit = 2)
        if (parts.size < 2) return null

        val volume = parts[0]
        val relativePath = parts[1]

        val basePath = if (volume == "primary") {
            "/storage/emulated/0"
        } else {
            "/storage/$volume"
        }

        val resolved = File(basePath, relativePath)
        return if (resolved.exists() || resolved.mkdirs()) resolved else null
    }

    fun resetExportProgress() {
        // Reset is handled by the export manager via its flow
    }

    class Factory(
        private val context: Context,
        private val retrogradeDatabase: RetrogradeDatabase,
        private val exportManager: GameExportManager,
        private val importManager: GameImportManager,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return TransferViewModel(context, retrogradeDatabase, exportManager, importManager) as T
        }
    }
}
