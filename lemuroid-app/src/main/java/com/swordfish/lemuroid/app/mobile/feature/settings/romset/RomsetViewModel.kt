package com.swordfish.lemuroid.app.mobile.feature.settings.romset

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.lib.romset.RomsetExportManager
import com.swordfish.lemuroid.lib.romset.RomsetImportManager
import com.swordfish.lemuroid.lib.romset.RomsetProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RomsetViewModel(
    private val context: Context,
    private val exportManager: RomsetExportManager,
    private val importManager: RomsetImportManager,
) : ViewModel() {

    data class UiState(
        val exportSizeBytes: Long = 0L,
        val destinationFreeBytes: Long = 0L,
        val exportProgress: RomsetProgress = RomsetProgress.Idle,
        val importProgress: RomsetProgress = RomsetProgress.Idle,
        val availableZipFiles: List<File> = emptyList(),
        val isSearchingMedia: Boolean = false,
    )

    private val _exportSizeBytes = MutableStateFlow(0L)
    private val _destinationFreeBytes = MutableStateFlow(0L)
    private val _availableZipFiles = MutableStateFlow<List<File>>(emptyList())
    private val _isSearchingMedia = MutableStateFlow(false)

    val uiState: StateFlow<UiState> = combine(
        exportManager.progress,
        importManager.progress,
        _exportSizeBytes,
        _destinationFreeBytes,
        _availableZipFiles,
    ) { exportProgress, importProgress, exportSize, destFree, zipFiles ->
        UiState(
            exportSizeBytes = exportSize,
            destinationFreeBytes = destFree,
            exportProgress = exportProgress,
            importProgress = importProgress,
            availableZipFiles = zipFiles,
            isSearchingMedia = _isSearchingMedia.value,
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, UiState())

    init {
        loadExportSize()
    }

    private fun loadExportSize() {
        viewModelScope.launch(Dispatchers.IO) {
            _exportSizeBytes.value = exportManager.calculateExportSize()
        }
    }

    fun resetExportProgress() {
        exportManager.resetProgress()
    }

    fun startExportToUri(treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val destDir = resolveFileFromTreeUri(treeUri) ?: return@launch
            val versionName = getAppVersionName()
            val destFile = File(destDir, "romset-$versionName.zip")
            exportManager.export(destFile)
        }
    }

    fun updateDestinationFreeSpace(treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = resolveFileFromTreeUri(treeUri)
            _destinationFreeBytes.value = if (dir != null) {
                runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(0L)
            } else 0L
        }
    }

    fun checkForImportableMedia() {
        viewModelScope.launch(Dispatchers.IO) {
            _isSearchingMedia.value = true
            _availableZipFiles.value = importManager.findRomsetOnVolumes()
            _isSearchingMedia.value = false
        }
    }

    fun startImportFromFile(zipFile: File) {
        viewModelScope.launch {
            importManager.importFromFile(zipFile)
        }
    }

    fun startImportFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    // Write to temp file then import (ZipInputStream requires two passes)
                    val tempFile = File(context.cacheDir, "romset_import_temp.zip")
                    tempFile.outputStream().use { stream.copyTo(it) }
                    importManager.importFromFile(tempFile)
                    tempFile.delete()
                }
            }.onFailure {
                importManager.resetProgress()
            }
        }
    }

    fun resetImportProgress() {
        importManager.resetProgress()
    }

    private fun getAppVersionName(): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1"
        }.getOrDefault("1")
    }

    private fun resolveFileFromTreeUri(treeUri: Uri): File? {
        val path = treeUri.path ?: return null
        val parts = path.substringAfter("/tree/").split(":", limit = 2)
        if (parts.size < 2) return null
        val volume = parts[0]
        val relativePath = parts[1]
        val basePath = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        val resolved = File(basePath, relativePath)
        return if (resolved.exists() || resolved.mkdirs()) resolved else null
    }

    class Factory(
        private val context: Context,
        private val exportManager: RomsetExportManager,
        private val importManager: RomsetImportManager,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return RomsetViewModel(context, exportManager, importManager) as T
        }
    }
}
