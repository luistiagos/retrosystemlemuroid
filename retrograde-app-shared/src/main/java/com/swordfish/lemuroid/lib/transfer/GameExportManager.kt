package com.swordfish.lemuroid.lib.transfer

import android.content.Context
import android.net.Uri
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

@OptIn(ExperimentalSerializationApi::class)
class GameExportManager(
    private val context: Context,
    private val directoriesManager: DirectoriesManager,
    private val retrogradeDatabase: RetrogradeDatabase,
) {
    private val _progress = MutableStateFlow<TransferProgress>(TransferProgress.Idle)
    val progress: StateFlow<TransferProgress> = _progress

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    suspend fun export(
        games: List<Game>,
        destDir: File,
        includeApk: Boolean,
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            _progress.value = TransferProgress.Idle

            val exportRoot = File(destDir, TransferManifest.EXPORT_DIR_NAME).apply { mkdirs() }
            val romsDir = File(exportRoot, TransferManifest.ROMS_DIR).apply { mkdirs() }
            val savesDir = File(exportRoot, TransferManifest.SAVES_DIR).apply { mkdirs() }
            val statesDir = File(exportRoot, TransferManifest.STATES_DIR).apply { mkdirs() }
            val previewsDir = File(exportRoot, TransferManifest.STATE_PREVIEWS_DIR).apply { mkdirs() }

            var apkFileName: String? = null
            if (includeApk) {
                _progress.value = TransferProgress.InProgress(
                    0, games.size, "", TransferProgress.InProgress.Phase.COPYING_APK,
                )
                apkFileName = exportApk(exportRoot)
            }

            val entries = mutableListOf<TransferGameEntry>()

            for ((index, game) in games.withIndex()) {
                _progress.value = TransferProgress.InProgress(
                    index, games.size, game.title, TransferProgress.InProgress.Phase.COPYING_ROMS,
                )

                // Copy ROM
                copyRomFile(game, romsDir)

                // Copy data files (multi-disc)
                val dataFiles = retrogradeDatabase.dataFileDao().selectDataFilesForGame(game.id)
                val dataFileEntries = dataFiles.map { dataFile ->
                    copyDataFile(dataFile.fileUri, dataFile.fileName, romsDir)
                    TransferDataFileEntry(
                        fileName = dataFile.fileName,
                        path = dataFile.path,
                    )
                }

                // Copy saves
                _progress.value = TransferProgress.InProgress(
                    index, games.size, game.title, TransferProgress.InProgress.Phase.COPYING_SAVES,
                )
                copySaves(game, savesDir)

                // Copy states and previews
                _progress.value = TransferProgress.InProgress(
                    index, games.size, game.title, TransferProgress.InProgress.Phase.COPYING_STATES,
                )
                copyStates(game, statesDir)
                copyStatePreviews(game, previewsDir)

                entries.add(
                    TransferGameEntry(
                        fileName = game.fileName,
                        title = game.title,
                        systemId = game.systemId,
                        developer = game.developer,
                        coverFrontUrl = game.coverFrontUrl,
                        isFavorite = game.isFavorite,
                        dataFiles = dataFileEntries,
                    ),
                )
            }

            // Write manifest
            _progress.value = TransferProgress.InProgress(
                games.size, games.size, "",
                TransferProgress.InProgress.Phase.WRITING_MANIFEST,
            )

            val manifest = TransferManifest(
                exportDate = System.currentTimeMillis(),
                appVersion = getAppVersionName(),
                appVersionCode = getAppVersionCode(),
                includesApk = includeApk && apkFileName != null,
                apkFileName = apkFileName,
                games = entries,
            )

            val manifestFile = File(exportRoot, TransferManifest.MANIFEST_FILE_NAME)
            manifestFile.writeText(json.encodeToString(manifest))

            _progress.value = TransferProgress.Completed(games.size)
            games.size
        }.onFailure { e ->
            Timber.e(e, "Export failed")
            _progress.value = TransferProgress.Error(e.message ?: "Unknown error")
        }
    }

    private fun exportApk(exportRoot: File): String? {
        return runCatching {
            val apkSource = File(context.applicationInfo.sourceDir)
            val versionName = getAppVersionName()
            val fileName = "lemuroid-v$versionName.apk"
            val apkDir = File(exportRoot, TransferManifest.APK_DIR).apply { mkdirs() }
            apkSource.copyTo(File(apkDir, fileName), overwrite = true)
            fileName
        }.onFailure { Timber.e(it, "Failed to export APK") }.getOrNull()
    }

    private fun copyRomFile(game: Game, romsDir: File) {
        val uri = Uri.parse(game.fileUri)
        when (uri.scheme) {
            "file" -> {
                val sourceFile = File(uri.path!!)
                if (sourceFile.exists() && sourceFile.length() > 0) {
                    sourceFile.copyTo(File(romsDir, game.fileName), overwrite = true)
                }
            }
            "content" -> {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    File(romsDir, game.fileName).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun copyDataFile(fileUri: String, fileName: String, romsDir: File) {
        val uri = Uri.parse(fileUri)
        when (uri.scheme) {
            "file" -> {
                val sourceFile = File(uri.path!!)
                if (sourceFile.exists()) {
                    sourceFile.copyTo(File(romsDir, fileName), overwrite = true)
                }
            }
            "content" -> {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    File(romsDir, fileName).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun copySaves(game: Game, savesDir: File) {
        val srmName = "${game.fileName.substringBeforeLast(".")}.srm"
        val sourceDir = directoriesManager.getSavesDirectory()
        val saveFile = File(sourceDir, srmName)
        if (saveFile.exists() && saveFile.length() > 0) {
            saveFile.copyTo(File(savesDir, srmName), overwrite = true)
        }
    }

    private fun copyStates(game: Game, statesBaseDir: File) {
        val sourceStatesDir = directoriesManager.getStatesDirectory()
        // States are organized by core: states/{coreName}/{gameFileName}.state etc.
        val coreSubDirs = sourceStatesDir.listFiles { file -> file.isDirectory } ?: return
        for (coreDir in coreSubDirs) {
            val matchingFiles = coreDir.listFiles { file ->
                file.name.startsWith(game.fileName)
            } ?: continue

            if (matchingFiles.isNotEmpty()) {
                val destCoreDir = File(statesBaseDir, coreDir.name).apply { mkdirs() }
                for (stateFile in matchingFiles) {
                    stateFile.copyTo(File(destCoreDir, stateFile.name), overwrite = true)
                }
            }
        }
    }

    private fun copyStatePreviews(game: Game, previewsBaseDir: File) {
        val sourcePreviewsDir = directoriesManager.getStatesPreviewDirectory()
        val coreSubDirs = sourcePreviewsDir.listFiles { file -> file.isDirectory } ?: return
        for (coreDir in coreSubDirs) {
            val matchingFiles = coreDir.listFiles { file ->
                file.name.startsWith(game.fileName)
            } ?: continue

            if (matchingFiles.isNotEmpty()) {
                val destCoreDir = File(previewsBaseDir, coreDir.name).apply { mkdirs() }
                for (previewFile in matchingFiles) {
                    previewFile.copyTo(File(destCoreDir, previewFile.name), overwrite = true)
                }
            }
        }
    }

    fun calculateExportSize(games: List<Game>, includeApk: Boolean): Long {
        var totalSize = 0L

        if (includeApk) {
            totalSize += File(context.applicationInfo.sourceDir).length()
        }

        for (game in games) {
            val uri = Uri.parse(game.fileUri)
            if (uri.scheme == "file") {
                val file = File(uri.path!!)
                if (file.exists()) totalSize += file.length()
            }

            val srmName = "${game.fileName.substringBeforeLast(".")}.srm"
            val saveFile = File(directoriesManager.getSavesDirectory(), srmName)
            if (saveFile.exists()) totalSize += saveFile.length()

            val statesDir = directoriesManager.getStatesDirectory()
            statesDir.listFiles { f -> f.isDirectory }?.forEach { coreDir ->
                coreDir.listFiles { f -> f.name.startsWith(game.fileName) }?.forEach { f ->
                    totalSize += f.length()
                }
            }
        }

        return totalSize
    }

    private fun getAppVersionName(): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        }.getOrDefault("unknown")
    }

    private fun getAppVersionCode(): Int {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).let {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    it.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    it.versionCode
                }
            }
        }.getOrDefault(0)
    }
}
