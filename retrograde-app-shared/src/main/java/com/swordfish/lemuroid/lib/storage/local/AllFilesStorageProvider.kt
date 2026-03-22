package com.swordfish.lemuroid.lib.storage.local

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import androidx.leanback.preference.LeanbackPreferenceFragment
import com.swordfish.lemuroid.common.kotlin.extractEntryToFile
import com.swordfish.lemuroid.common.kotlin.isZipped
import com.swordfish.lemuroid.lib.R
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.db.entity.DataFile
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.BaseStorageFile
import com.swordfish.lemuroid.lib.storage.RomFiles
import com.swordfish.lemuroid.lib.storage.StorageFile
import com.swordfish.lemuroid.lib.storage.StorageProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

class AllFilesStorageProvider(
    private val context: Context
) : StorageProvider {
    override val id: String = "all_files"

    override val name: String = context.getString(R.string.all_files_storage)

    override val uriSchemes = listOf("file")

    override val prefsFragmentClass: Class<LeanbackPreferenceFragment>? = null

    override val enabledByDefault = false

    override fun listBaseStorageFiles(): Flow<List<BaseStorageFile>> = flow {
        val rootDirectory = Environment.getExternalStorageDirectory()
        val supportedExtensions = GameSystem.getSupportedExtensions().map { it.removePrefix(".") }

        Timber.d("AllFilesStorageProvider: Starting root scan at ${rootDirectory.absolutePath}")
        
        val directories = mutableListOf(rootDirectory)

        while (directories.isNotEmpty()) {
            val directory = directories.removeAt(0)
            
            // Skip problematic or slow system folders that don't usually contain ROMs
            if (directory.name.startsWith(".") || directory.name == "Android") {
                continue
            }

            val groups = directory.listFiles()
                ?.filterNot { it.name.startsWith(".") }
                ?.groupBy { it.isDirectory } ?: mapOf()

            val newDirectories = groups[true] ?: listOf()
            val newFiles = groups[false] ?: listOf()

            directories.addAll(newDirectories)
            
            val validRoms = newFiles.filter { it.extension.lowercase() in supportedExtensions }
            
            if (validRoms.isNotEmpty()) {
                Timber.d("AllFilesStorageProvider: Found ${validRoms.size} files in ${directory.absolutePath}")
                emit(validRoms.map { BaseStorageFile(it.name, it.length(), it.toUri(), it.path) })
            }
        }
    }

    override fun getStorageFile(baseStorageFile: BaseStorageFile): StorageFile? {
        return DocumentFileParser.parseDocumentFile(context, baseStorageFile)
    }

    private fun getDataFile(dataFile: DataFile): File {
        val dataFilePath = Uri.parse(dataFile.fileUri).path
        return File(dataFilePath!!)
    }

    private fun getGameRom(game: Game): File {
        val gamePath = Uri.parse(game.fileUri).path
        val originalFile = File(gamePath!!)
        if (!originalFile.isZipped() || originalFile.name == game.fileName) {
            return originalFile
        }

        val cacheFile = GameCacheUtils.getCacheFileForGame(ALL_FILES_STORAGE_CACHE_SUBFOLDER, context, game)
        if (cacheFile.exists()) {
            return cacheFile
        }

        if (originalFile.isZipped()) {
            val stream = ZipInputStream(originalFile.inputStream())
            stream.extractEntryToFile(game.fileName, cacheFile)
        }

        return cacheFile
    }

    override fun getGameRomFiles(
        game: Game,
        dataFiles: List<DataFile>,
        allowVirtualFiles: Boolean,
    ): RomFiles {
        return RomFiles.Standard(listOf(getGameRom(game)) + dataFiles.map { getDataFile(it) })
    }

    override fun getInputStream(uri: Uri): InputStream {
        return File(uri.path!!).inputStream()
    }

    companion object {
        const val ALL_FILES_STORAGE_CACHE_SUBFOLDER = "all-files-storage-games"
    }
}
