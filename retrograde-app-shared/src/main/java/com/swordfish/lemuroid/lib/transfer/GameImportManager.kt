package com.swordfish.lemuroid.lib.transfer

import android.content.Context
import android.net.Uri
import com.swordfish.lemuroid.lib.library.LemuroidLibrary
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

class GameImportManager(
    private val context: Context,
    private val directoriesManager: DirectoriesManager,
    private val retrogradeDatabase: RetrogradeDatabase,
    private val lemuroidLibrary: LemuroidLibrary,
) {
    private val _progress = MutableStateFlow<TransferProgress>(TransferProgress.Idle)
    val progress: StateFlow<TransferProgress> = _progress

    private val json = Json { ignoreUnknownKeys = true }

    fun findManifestOnVolumes(): File? {
        val volumes = context.getExternalFilesDirs(null).filterNotNull()
        for (volume in volumes) {
            // Check in parent directories to find lemuroid-export at the volume root
            var dir: File? = volume
            while (dir != null && dir.absolutePath.contains("Android")) {
                dir = dir.parentFile
            }
            if (dir != null) {
                val manifestFile = File(dir, "${TransferManifest.EXPORT_DIR_NAME}/${TransferManifest.MANIFEST_FILE_NAME}")
                if (manifestFile.exists()) return manifestFile
            }
        }
        return null
    }

    fun readManifest(manifestFile: File): TransferManifest? {
        return runCatching {
            json.decodeFromString(TransferManifest.serializer(), manifestFile.readText())
        }.onFailure { Timber.e(it, "Failed to read manifest") }.getOrNull()
    }

    suspend fun import(
        manifestFile: File,
        manifest: TransferManifest,
        selectedGames: List<TransferGameEntry>? = null,
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            _progress.value = TransferProgress.Idle

            val exportRoot = manifestFile.parentFile
                ?: throw IllegalStateException("Cannot resolve export directory")

            val romsSourceDir = File(exportRoot, TransferManifest.ROMS_DIR)
            val savesSourceDir = File(exportRoot, TransferManifest.SAVES_DIR)
            val statesSourceDir = File(exportRoot, TransferManifest.STATES_DIR)
            val previewsSourceDir = File(exportRoot, TransferManifest.STATE_PREVIEWS_DIR)

            val gamesToImport = selectedGames ?: manifest.games
            val romsDestDir = directoriesManager.getInternalRomsDirectory()
            val savesDestDir = directoriesManager.getSavesDirectory()
            val statesDestDir = directoriesManager.getStatesDirectory()
            val previewsDestDir = directoriesManager.getStatesPreviewDirectory()

            for ((index, entry) in gamesToImport.withIndex()) {
                _progress.value = TransferProgress.InProgress(
                    index, gamesToImport.size, entry.title,
                    TransferProgress.InProgress.Phase.COPYING_ROMS,
                )

                // Copy ROM
                val romSource = File(romsSourceDir, entry.fileName)
                if (romSource.exists()) {
                    romSource.copyTo(File(romsDestDir, entry.fileName), overwrite = true)
                }

                // Copy data files (multi-disc)
                for (dataFile in entry.dataFiles) {
                    val dataSource = File(romsSourceDir, dataFile.fileName)
                    if (dataSource.exists()) {
                        dataSource.copyTo(File(romsDestDir, dataFile.fileName), overwrite = true)
                    }
                }

                // Copy saves
                _progress.value = TransferProgress.InProgress(
                    index, gamesToImport.size, entry.title,
                    TransferProgress.InProgress.Phase.COPYING_SAVES,
                )
                val srmName = "${entry.fileName.substringBeforeLast(".")}.srm"
                val saveSource = File(savesSourceDir, srmName)
                if (saveSource.exists()) {
                    saveSource.copyTo(File(savesDestDir, srmName), overwrite = true)
                }

                // Copy states
                _progress.value = TransferProgress.InProgress(
                    index, gamesToImport.size, entry.title,
                    TransferProgress.InProgress.Phase.COPYING_STATES,
                )
                if (statesSourceDir.exists()) {
                    statesSourceDir.listFiles { f -> f.isDirectory }?.forEach { coreDir ->
                        coreDir.listFiles { f -> f.name.startsWith(entry.fileName) }?.forEach { stateFile ->
                            val destCoreDir = File(statesDestDir, coreDir.name).apply { mkdirs() }
                            stateFile.copyTo(File(destCoreDir, stateFile.name), overwrite = true)
                        }
                    }
                }

                // Copy state previews
                if (previewsSourceDir.exists()) {
                    previewsSourceDir.listFiles { f -> f.isDirectory }?.forEach { coreDir ->
                        coreDir.listFiles { f -> f.name.startsWith(entry.fileName) }?.forEach { previewFile ->
                            val destCoreDir = File(previewsDestDir, coreDir.name).apply { mkdirs() }
                            previewFile.copyTo(File(destCoreDir, previewFile.name), overwrite = true)
                        }
                    }
                }
            }

            // Trigger library re-scan to pick up imported ROMs
            _progress.value = TransferProgress.InProgress(
                gamesToImport.size, gamesToImport.size, "",
                TransferProgress.InProgress.Phase.WRITING_MANIFEST,
            )
            lemuroidLibrary.indexLibrary()

            // Update favorites for imported games
            updateFavorites(gamesToImport)

            _progress.value = TransferProgress.Completed(gamesToImport.size)
            gamesToImport.size
        }.onFailure { e ->
            Timber.e(e, "Import failed")
            _progress.value = TransferProgress.Error(e.message ?: "Unknown error")
        }
    }

    private suspend fun updateFavorites(entries: List<TransferGameEntry>) {
        for (entry in entries) {
            if (entry.isFavorite) {
                val romsDir = directoriesManager.getInternalRomsDirectory()
                val fileUri = Uri.fromFile(File(romsDir, entry.fileName)).toString()
                val game = retrogradeDatabase.gameDao().selectByFileUri(fileUri)
                if (game != null && !game.isFavorite) {
                    retrogradeDatabase.gameDao().update(game.copy(isFavorite = true))
                }
            }
        }
    }

    fun getApkFile(manifestFile: File, manifest: TransferManifest): File? {
        if (!manifest.includesApk || manifest.apkFileName == null) return null
        val exportRoot = manifestFile.parentFile ?: return null
        val apkFile = File(exportRoot, "${TransferManifest.APK_DIR}/${manifest.apkFileName}")
        return if (apkFile.exists()) apkFile else null
    }
}
