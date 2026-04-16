package com.swordfish.lemuroid.lib.romset

import android.content.Context
import android.net.Uri
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RomsetExportManager(
    private val context: Context,
    private val directoriesManager: DirectoriesManager,
    private val retrogradeDatabase: RetrogradeDatabase,
) {
    private val _progress = MutableStateFlow<RomsetProgress>(RomsetProgress.Idle)
    val progress: StateFlow<RomsetProgress> = _progress

    fun resetProgress() {
        _progress.value = RomsetProgress.Idle
    }

    suspend fun export(destFile: File): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            _progress.value = RomsetProgress.Idle

            val romsBaseDir = directoriesManager.getInternalRomsDirectory()

            // Primary: collect files via DB records (downloadedRomDao is authoritative)
            val downloadedNames = retrogradeDatabase.downloadedRomDao().getAllDownloadedFileNames().toSet()
            val dbFiles = retrogradeDatabase.gameDao().selectAll()
                .filter { it.fileName in downloadedNames }
                .mapNotNull { game ->
                    Uri.parse(game.fileUri).path?.let { File(it) }
                        ?.takeIf { it.isFile && it.length() > 0 }
                }

            // Fallback: filesystem scan for locally added ROMs not recorded in downloadedRomDao
            val fsFiles = romsBaseDir.walkTopDown()
                .filter { it.isFile && it.length() > 0 }
                .toList()

            val romFiles = (dbFiles + fsFiles).distinctBy { it.absolutePath }

            if (romFiles.isEmpty()) {
                _progress.value = RomsetProgress.Completed(0)
                return@runCatching 0
            }

            ZipOutputStream(FileOutputStream(destFile)).use { zip ->
                for ((index, file) in romFiles.withIndex()) {
                    _progress.value = RomsetProgress.InProgress(
                        currentIndex = index,
                        totalCount = romFiles.size,
                        currentFileName = file.name,
                        phase = RomsetProgress.InProgress.Phase.COMPRESSING,
                    )

                    // Preserve the folder structure relative to the romsBaseDir
                    val entryName = file.relativeTo(romsBaseDir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }

            _progress.value = RomsetProgress.Completed(romFiles.size)
            romFiles.size
        }.onFailure { e ->
            Timber.e(e, "Romset export failed")
            _progress.value = RomsetProgress.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun exportToUri(treeUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val destDir = resolveFileFromTreeUri(treeUri)
                ?: throw IllegalArgumentException("Could not resolve destination directory")

            val versionName = getAppVersionName()
            val destFile = File(destDir, "romset-$versionName.zip")
            export(destFile).getOrThrow()
        }.onFailure { e ->
            Timber.e(e, "Romset export to URI failed")
            _progress.value = RomsetProgress.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun calculateExportSize(): Long = withContext(Dispatchers.IO) {
        val downloadedNames = retrogradeDatabase.downloadedRomDao().getAllDownloadedFileNames().toSet()
        val dbSize = retrogradeDatabase.gameDao().selectAll()
            .filter { it.fileName in downloadedNames }
            .sumOf { game ->
                Uri.parse(game.fileUri).path?.let { File(it) }
                    ?.takeIf { it.isFile && it.length() > 0 }?.length() ?: 0L
            }
        // Add any locally-added ROMs not in downloadedRomDao
        val romsBaseDir = directoriesManager.getInternalRomsDirectory()
        val fsSize = romsBaseDir.walkTopDown().filter { it.isFile && it.length() > 0 }.sumOf { it.length() }
        maxOf(dbSize, fsSize)
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
}
