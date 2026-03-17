package com.swordfish.lemuroid.app.shared.roms

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.swordfish.lemuroid.BuildConfig
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

sealed class DownloadRomsState {
    object Idle : DownloadRomsState()
    data class Downloading(val progress: Float) : DownloadRomsState()
    data class Extracting(val progress: Float) : DownloadRomsState()
    object Done : DownloadRomsState()
    data class Error(val message: String) : DownloadRomsState()
}

class RomsDownloadManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "home_download_prefs"
        const val PREF_DOWNLOAD_DONE = "download_done"
        private const val TAG = "RomsDownload"

        /**
         * Bump this value whenever the extraction/normalization logic changes
         * in a way that requires re-processing existing downloads.
         * If the stored version is lower than this, the download is reset to Idle.
         */
        private const val EXTRACTION_VERSION = 6
        private const val PREF_EXTRACTION_VERSION = "extraction_version"

        /**
         * All valid system dbnames. Used by unwrapWrapperFolders to avoid accidentally
         * treating an already-correctly-named folder (e.g. "md/") as a wrapper.
         */
        val SYSTEM_DBNAMES = setOf(
            "nes", "snes", "md", "gb", "gbc", "gba", "n64", "sms", "psp", "nds",
            "gg", "atari2600", "psx", "fbneo", "mame2003plus", "pce", "lynx",
            "atari7800", "scd", "ngp", "ngc", "ws", "wsc", "dos", "3ds",
        )

        /**
         * Maps human-readable folder names (as they may appear inside the archive)
         * to the dbname values that Lemuroid uses to identify systems.
         * Case-insensitive matching is applied at runtime.
         */
        val FOLDER_NAME_MAP = mapOf(
            "arcade"               to "fbneo",
            "atari 2600"           to "atari2600",
            "atari 7800"           to "atari7800",
            "game boy"             to "gb",
            "game boy advance"     to "gba",
            "game boy color"       to "gbc",
            "gameboy"              to "gb",
            "gameboy advance"      to "gba",
            "gameboy color"        to "gbc",
            "mastersystem"         to "sms",
            "master system"        to "sms",
            "sega master system"   to "sms",
            "megadrive"            to "md",
            "mega drive"           to "md",
            "genesis"              to "md",
            "sega megadrive"       to "md",
            "sega genesis"         to "md",
            "neo geo pocket"       to "ngp",
            "neo-geo pocket"       to "ngp",
            "ngpc"                 to "ngc",
            "neo geo pocket color" to "ngc",
            "nintendo"             to "nes",
            "nes"                  to "nes",
            "nintendo 64"          to "n64",
            "n64"                  to "n64",
            "nintendo ds"          to "nds",
            "nds"                  to "nds",
            "playstation"          to "psx",
            "psx"                  to "psx",
            "ps1"                  to "psx",
            "ps one"               to "psx",
            "playstation portable" to "psp",
            "psp"                  to "psp",
            "super nintendo"       to "snes",
            "snes"                 to "snes",
            "super nes"            to "snes",
            "pc engine"            to "pce",
            "pc-engine"            to "pce",
            "turbografx"           to "pce",
            "turbografx-16"        to "pce",
            "game gear"            to "gg",
            "gamegear"             to "gg",
            "lynx"                 to "lynx",
            "atari lynx"           to "lynx",
            "wonderswan"           to "ws",
            "wonder swan"          to "ws",
            "wonderswan color"     to "wsc",
            "wonder swan color"    to "wsc",
            "sega cd"              to "scd",
            "mega cd"              to "scd",
            "segacd"               to "scd",
            "megacd"               to "scd",
            "sega-cd"              to "scd",
            "mega-cd"              to "scd",
            "mame"                 to "mame2003plus",
            "mame 2003"            to "mame2003plus",
            "mame2003"             to "mame2003plus",
            "mame 2003 plus"       to "mame2003plus",
            "mame2003plus"         to "mame2003plus",
        )
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val httpClient: OkHttpClient by lazy { buildHttpClient() }

    private val initialState: DownloadRomsState = run {
        val done = prefs.getBoolean(PREF_DOWNLOAD_DONE, false)
        val storedVersion = prefs.getInt(PREF_EXTRACTION_VERSION, 0)
        if (done && storedVersion >= EXTRACTION_VERSION) DownloadRomsState.Done else {
            // Reset so the user sees the download card again and a fresh extraction runs
            if (done) prefs.edit().putBoolean(PREF_DOWNLOAD_DONE, false).apply()
            DownloadRomsState.Idle
        }
    }

    private val _state = MutableStateFlow<DownloadRomsState>(initialState)
    val state: Flow<DownloadRomsState> = _state

    fun isDownloadDone(): Boolean =
        prefs.getBoolean(PREF_DOWNLOAD_DONE, false) &&
        prefs.getInt(PREF_EXTRACTION_VERSION, 0) >= EXTRACTION_VERSION

    fun downloadAndExtract(scope: CoroutineScope) {
        val current = _state.value
        if (current is DownloadRomsState.Downloading || current is DownloadRomsState.Extracting) return
        // Set state before launching so concurrent calls see it immediately
        _state.value = DownloadRomsState.Downloading(0f)
        scope.launch(Dispatchers.IO) {
            try {
                val romsDir = DirectoriesManager(appContext).getInternalRomsDirectory()
                romsDir.listFiles()?.forEach { it.deleteRecursively() }
                romsDir.mkdirs()

                val downloadUrl = "https://huggingface.co/datasets/Emuladores/sets/resolve/main/romssnesnds.7z?download=true"
                val archiveFile = File(romsDir, "roms_download.7z")
                Log.d(TAG, "Starting download")
                downloadFile(downloadUrl, archiveFile) { progress ->
                    _state.value = DownloadRomsState.Downloading(progress)
                }

                Log.d(TAG, "archiveFile size=${archiveFile.length()}, extracting")
                _state.value = DownloadRomsState.Extracting(0f)
                extractSevenZ(archiveFile, romsDir) { progress ->
                    _state.value = DownloadRomsState.Extracting(progress)
                }

                archiveFile.delete()
                normalizeExtractedFolders(romsDir)

                // If the user has selected a SAF directory, move extracted ROMs there
                val safUriStr = getLegacyPrefs().getString(
                    appContext.getString(com.swordfish.lemuroid.lib.R.string.pref_key_extenral_folder), null
                )
                if (!safUriStr.isNullOrEmpty()) {
                    val safTree = DocumentFile.fromTreeUri(appContext, Uri.parse(safUriStr))
                    if (safTree != null && safTree.canWrite()) {
                        Log.d(TAG, "Copying extracted ROMs to SAF dir: $safUriStr")
                        val extractedFiles = romsDir.walkTopDown().filter { it.isFile }.toList()
                        extractedFiles.forEachIndexed { index, file ->
                            copyFileToSaf(file, romsDir, safTree)
                            _state.value = DownloadRomsState.Extracting(0.99f + 0.01f * (index + 1) / extractedFiles.size)
                        }
                        romsDir.deleteRecursively()
                        romsDir.mkdirs()
                        Log.d(TAG, "Moved ${extractedFiles.size} files to SAF directory")
                    } else {
                        Log.w(TAG, "SAF tree not writable, ROMs left in internal storage")
                    }
                }

                LibraryIndexScheduler.scheduleLibrarySync(appContext)
                prefs.edit()
                    .putBoolean(PREF_DOWNLOAD_DONE, true)
                    .putInt(PREF_EXTRACTION_VERSION, EXTRACTION_VERSION)
                    .apply()
                _state.value = DownloadRomsState.Done
            } catch (e: Throwable) {
                Log.e(TAG, "Download/extract failed", e)
                _state.value = DownloadRomsState.Error("${e.javaClass.simpleName}: ${e.message ?: "(no message)"}")
            }
        }
    }

    private fun getLegacyPrefs(): SharedPreferences =
        @Suppress("DEPRECATION")
        SharedPreferencesHelper.getLegacySharedPreferences(appContext)

    /**
     * Recursively renames folders anywhere under [romsDir] using [FOLDER_NAME_MAP].
     * Also unwraps any unrecognised single-level wrapper folder (e.g. the archive
     * may extract everything inside a root "roms/" folder whose name is not a system
     * dbname — in that case its contents are moved up into [romsDir] directly).
     * Handles merge conflicts when the target folder already exists.
     */
    private fun normalizeExtractedFolders(romsDir: File) {
        // First pass: unwrap unrecognised wrapper folders that sit between romsDir
        // and the actual system-named (or file-containing) content.
        unwrapWrapperFolders(romsDir)

        // Second pass: rename every system-named folder at any depth.
        renameSystemFoldersRecursive(romsDir)
    }

    /**
     * If [dir] contains a single subdirectory whose name is NOT in [FOLDER_NAME_MAP]
     * (i.e. it's an archive wrapper like "roms/"), move all its contents one level up
     * and delete it. Repeats until the outermost folder IS a system folder or has files.
     */
    private fun unwrapWrapperFolders(dir: File) {
        val children = dir.listFiles() ?: return
        val subDirs = children.filter { it.isDirectory }
        val files = children.filter { it.isFile }

        // Only unwrap when the folder contains exactly one subdir and no direct files,
        // and that subdir's name is neither a known human-readable alias nor a system dbname.
        // Without the dbname check, a folder already named "md" would incorrectly be unwrapped.
        if (files.isEmpty() && subDirs.size == 1) {
            val wrapper = subDirs[0]
            val wrapperLower = wrapper.name.lowercase()
            if (FOLDER_NAME_MAP[wrapperLower] == null && !SYSTEM_DBNAMES.contains(wrapperLower)) {
                Log.d(TAG, "Unwrapping wrapper folder '${wrapper.name}' into '${dir.name}'")
                wrapper.listFiles()?.forEach { child ->
                    val dest = File(dir, child.name)
                    // Use copy+delete instead of renameTo to avoid silent failures
                    // when the destination already exists.
                    if (!safeMoveFile(child, dest)) {
                        Log.w(TAG, "Failed to move '${child.name}' while unwrapping wrapper")
                    }
                }
                wrapper.deleteRecursively()
                // Recurse in case there are multiple levels of wrapping
                unwrapWrapperFolders(dir)
            }
        }
    }

    /** Depth-first rename of every folder whose lowercase name is in [FOLDER_NAME_MAP]. */
    private fun renameSystemFoldersRecursive(dir: File) {
        val children = dir.listFiles() ?: return
        children.filter { it.isDirectory }.forEach { folder ->
            val mapped = FOLDER_NAME_MAP[folder.name.lowercase()]
            if (mapped != null) {
                val target = File(dir, mapped)
                if (target.absolutePath == folder.absolutePath) {
                    // Already the correct name; still recurse inside
                    renameSystemFoldersRecursive(folder)
                    return@forEach
                }
                if (target.exists()) {
                    // Merge: copy each file from source folder into target.
                    // Use copy+delete (safeMoveFile) instead of renameTo to avoid
                    // silent failures when a file with the same name already exists.
                    folder.walkTopDown().filter { it.isFile }.forEach { file ->
                        val rel = file.relativeTo(folder)
                        val dest = File(target, rel.path)
                        dest.parentFile?.mkdirs()
                        if (!safeMoveFile(file, dest)) {
                            Log.w(TAG, "Failed to merge '${file.name}' into '$mapped'")
                        }
                    }
                    folder.deleteRecursively()
                } else {
                    // Target does not exist: simple rename is safe here.
                    if (!folder.renameTo(target)) {
                        Log.w(TAG, "renameTo failed for '${folder.name}' → '$mapped', falling back to copy")
                        folder.walkTopDown().filter { it.isFile }.forEach { file ->
                            val rel = file.relativeTo(folder)
                            val dest = File(target, rel.path)
                            dest.parentFile?.mkdirs()
                            safeMoveFile(file, dest)
                        }
                        folder.deleteRecursively()
                    }
                }
                Log.d(TAG, "Renamed folder '${folder.name}' → '$mapped'")
                // Recurse into the now-renamed folder
                renameSystemFoldersRecursive(target)
            } else {
                // Unknown folder name: recurse looking for system folders inside
                renameSystemFoldersRecursive(folder)
            }
        }
    }

    /**
     * Moves [src] to [dest] safely: if [dest] already exists it is overwritten.
     * Handles both files and directories. Uses copy+delete as fallback when
     * [File.renameTo] fails (e.g. cross-filesystem move on some devices).
     * Returns true on success.
     */
    private fun safeMoveFile(src: File, dest: File): Boolean {
        return try {
            if (dest.exists()) {
                if (dest.isDirectory) dest.deleteRecursively() else dest.delete()
            }
            if (src.renameTo(dest)) return true
            // Fallback for files: copy bytes then delete source.
            // Directories are not expected to reach this path (same-filesystem rename
            // should always succeed), but handle gracefully just in case.
            if (src.isDirectory) {
                src.walkTopDown().filter { it.isFile }.forEach { file ->
                    val rel = file.relativeTo(src)
                    val destFile = File(dest, rel.path)
                    destFile.parentFile?.mkdirs()
                    file.inputStream().use { i -> destFile.outputStream().use { o -> i.copyTo(o) } }
                }
                src.deleteRecursively()
            } else {
                src.inputStream().use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                src.delete()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "safeMoveFile failed: ${src.path} → ${dest.path}", e)
            false
        }
    }

    private fun copyFileToSaf(file: File, sourceRoot: File, destTree: DocumentFile) {
        val parts = file.relativeTo(sourceRoot).path.split(File.separator).filter { it.isNotEmpty() }
        var currentDoc = destTree
        parts.dropLast(1).forEach { dirName ->
            currentDoc = currentDoc.findFile(dirName) ?: currentDoc.createDirectory(dirName)
                ?: run { Log.w(TAG, "SAF: could not create dir '$dirName' for ${file.name}"); return }
        }
        val destFile = currentDoc.createFile("application/octet-stream", file.name)
            ?: run { Log.w(TAG, "SAF: createFile failed for ${file.name}"); return }
        val out = appContext.contentResolver.openOutputStream(destFile.uri)
            ?: run { Log.w(TAG, "SAF: openOutputStream failed for ${file.name}"); return }
        out.use { file.inputStream().use { input -> input.copyTo(it) } }
    }

    private fun buildHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)   // 0 = no timeout (needed for large archives)
            .followRedirects(true)
            .followSslRedirects(true)
        // Only bypass SSL validation on debug builds (e.g. emulators with outdated CAs).
        // Production builds use the default system trust store.
        if (BuildConfig.DEBUG) {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            }
            builder
                .sslSocketFactory(sslContext.socketFactory, trustAll)
                .hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    private suspend fun downloadFile(url: String, destination: File, onProgress: (Float) -> Unit) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) LemuroidApp/1.0")
            .build()
        httpClient.newCall(request).execute().use { response ->
            Log.d(TAG, "HTTP ${response.code} for $url")
            if (!response.isSuccessful) throw IOException("HTTP error ${response.code}: ${response.message}")
            val body = response.body ?: throw IOException("Empty response body")
            val contentLength = body.contentLength()
            destination.parentFile?.mkdirs()
            FileOutputStream(destination).use { outputStream ->
                body.byteStream().use { inputStream ->
                    val buffer = ByteArray(32 * 1024)
                    var bytesRead = 0L
                    var n: Int
                    while (inputStream.read(buffer).also { n = it } != -1) {
                        outputStream.write(buffer, 0, n)
                        bytesRead += n
                        if (contentLength > 0) onProgress((bytesRead.toFloat() / contentLength).coerceAtMost(1f))
                    }
                }
            }
        }
        Log.d(TAG, "Downloaded ${destination.name}: ${destination.length()} bytes")
    }

    private fun extractSevenZ(archiveFile: File, destDir: File, onProgress: (Float) -> Unit) {
        val canonicalDest = destDir.canonicalPath
        Log.d(TAG, "Opening 7z: ${archiveFile.absolutePath}")
        SevenZFile.builder().setFile(archiveFile).get().use { sevenZFile ->
            var totalSize = 0L
            for (entry in sevenZFile.entries) {
                if (!entry.isDirectory) totalSize += entry.size
            }
            Log.d(TAG, "7z total uncompressed size: $totalSize bytes")
            var extractedBytes = 0L
            var entry = sevenZFile.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val entryPath = entry.name.replace('\\', '/')
                    val outFile = File(destDir, entryPath)
                    if (!outFile.canonicalPath.startsWith(canonicalDest + File.separator)) {
                        Log.w(TAG, "Skipping path traversal: ${entry.name}")
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            val buf = ByteArray(32 * 1024)
                            var n: Int
                            while (sevenZFile.read(buf).also { n = it } != -1) {
                                out.write(buf, 0, n)
                                extractedBytes += n
                                if (totalSize > 0) onProgress((extractedBytes.toFloat() / totalSize).coerceAtMost(0.99f))
                            }
                        }
                    }
                }
                entry = sevenZFile.nextEntry
            }
        }
        onProgress(1f)
    }
}
