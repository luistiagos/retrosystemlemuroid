package com.swordfish.lemuroid.lib.storage.local

import android.content.Context
import com.swordfish.lemuroid.lib.library.db.entity.DataFile
import com.swordfish.lemuroid.lib.library.db.entity.Game
import java.io.File

object GameCacheUtils {

    /**
     * Extensions that indicate a disc image entry inside a zip archive.
     * Presence of any of these in the zip's central directory means the zip
     * is a multi-file disc set (CUE+BIN, GDI+BIN) that needs full extraction,
     * rather than an arcade ROM zip that should be passed directly to the core.
     */
    val DISC_IMAGE_EXTENSIONS = setOf("cue", "gdi", "iso", "chd")

    /**
     * Priority order for selecting the main playable file inside an extracted
     * multi-disc directory.  .cue and .gdi are the preferred entry points;
     * .iso and .chd are single-file formats that work without a companion sheet.
     */
    private val MAIN_FILE_PRIORITY = listOf("cue", "gdi", "iso", "chd")

    /**
     * Returns the main playable disc-image file inside [dir] (e.g. the .cue
     * sheet), chosen by [MAIN_FILE_PRIORITY], or null if none is found.
     */
    fun findMainDiscFile(dir: File): File? {
        if (!dir.isDirectory) return null
        val files = dir.listFiles() ?: return null
        for (ext in MAIN_FILE_PRIORITY) {
            val found = files.firstOrNull { it.extension.lowercase() == ext }
            if (found != null) return found
        }
        return null
    }
    fun getDataFileForGame(
        folderName: String,
        context: Context,
        game: Game,
        dataFile: DataFile,
    ): File {
        val gamesCacheDir = getCacheDirForGame(folderName, game, context)
        return File(gamesCacheDir, dataFile.fileName)
    }

    fun getCacheFileForGame(
        folderName: String,
        context: Context,
        game: Game,
    ): File {
        val gamesCacheDir = getCacheDirForGame(folderName, game, context)
        return File(gamesCacheDir, game.fileName)
    }

    private fun getCacheDirForGame(
        folderName: String,
        game: Game,
        context: Context,
    ): File {
        val gamesCachePath = buildPath(folderName, game.systemId)
        val gamesCacheDir = File(context.cacheDir, gamesCachePath)
        gamesCacheDir.mkdirs()
        return gamesCacheDir
    }

    private fun buildPath(vararg chunks: String): String {
        return chunks.joinToString(separator = File.separator)
    }
}
