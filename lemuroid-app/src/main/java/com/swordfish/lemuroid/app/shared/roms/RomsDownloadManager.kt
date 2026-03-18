package com.swordfish.lemuroid.app.shared.roms

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.swordfish.lemuroid.BuildConfig
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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

        /** Set to true as soon as a download is enqueued; cleared on completion or cancel. */
        const val PREF_DOWNLOAD_STARTED = "download_started"

        /** Set to true after the .7z archive is fully downloaded; cleared on completion or cancel. */
        private const val PREF_ARCHIVE_DOWNLOADED = "archive_downloaded"

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

    init {
        // If a download was started but never completed (app was killed mid-download or
        // mid-extraction), automatically re-enqueue the work so it resumes from where it
        // left off. ExistingWorkPolicy.KEEP means no duplicate if already running.
        if (prefs.getBoolean(PREF_DOWNLOAD_STARTED, false) && !isDownloadDone()) {
            RomsDownloadWork.enqueue(appContext)
        }
    }

    // State is derived from WorkManager's WorkInfo so it stays accurate even after screen lock.
    val state: Flow<DownloadRomsState> = WorkManager.getInstance(appContext)
        .getWorkInfosForUniqueWorkFlow(RomsDownloadWork.UNIQUE_WORK_ID)
        .map { workInfos ->
            val info = workInfos.firstOrNull()
            when {
                info == null -> initialState
                info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.BLOCKED ->
                    DownloadRomsState.Downloading(0f)
                info.state == WorkInfo.State.RUNNING -> {
                    val phase = info.progress.getString(RomsDownloadWork.KEY_PHASE)
                        ?: RomsDownloadWork.PHASE_DOWNLOADING
                    val progress = info.progress.getFloat(RomsDownloadWork.KEY_PROGRESS, 0f)
                    if (phase == RomsDownloadWork.PHASE_EXTRACTING)
                        DownloadRomsState.Extracting(progress)
                    else
                        DownloadRomsState.Downloading(progress)
                }
                info.state == WorkInfo.State.SUCCEEDED -> DownloadRomsState.Done
                info.state == WorkInfo.State.FAILED -> {
                    val error = info.outputData.getString(RomsDownloadWork.KEY_ERROR) ?: "Unknown error"
                    DownloadRomsState.Error(error)
                }
                else -> initialState
            }
        }

    fun isDownloadDone(): Boolean =
        prefs.getBoolean(PREF_DOWNLOAD_DONE, false) &&
        prefs.getInt(PREF_EXTRACTION_VERSION, 0) >= EXTRACTION_VERSION

    fun isDownloadStarted(): Boolean = prefs.getBoolean(PREF_DOWNLOAD_STARTED, false)

    /**
     * Clears PREF_DOWNLOAD_STARTED without touching any other flag.
     * Called by RomsDownloadWork when the work reaches a permanent failure state so
     * that the init-block auto-resume doesn't fire on every subsequent app open.
     */
    fun clearDownloadStarted() {
        prefs.edit().putBoolean(PREF_DOWNLOAD_STARTED, false).apply()
    }

    fun cancelDownload() {
        WorkManager.getInstance(appContext).cancelUniqueWork(RomsDownloadWork.UNIQUE_WORK_ID)
        // Delete all remnant files so the next download starts from scratch.
        val romsDir = DirectoriesManager(appContext).getInternalRomsDirectory()
        romsDir.deleteRecursively()
        prefs.edit()
            .putBoolean(PREF_DOWNLOAD_STARTED, false)
            .putBoolean(PREF_ARCHIVE_DOWNLOADED, false)
            .remove(PREF_DOWNLOAD_DONE)
            .apply()
        Log.d(TAG, "Download cancelled and all files deleted")
    }

    @Suppress("UNUSED_PARAMETER")
    fun downloadAndExtract(scope: Any? = null) {
        prefs.edit().putBoolean(PREF_DOWNLOAD_STARTED, true).apply()
        RomsDownloadWork.enqueue(appContext)
    }

    internal suspend fun doDownload(onProgress: suspend (phase: String, progress: Float) -> Unit) {
        val romsDir = DirectoriesManager(appContext).getInternalRomsDirectory()
        romsDir.mkdirs()
        val archiveFile = File(romsDir, "roms_download.7z")

        // If the archive was already fully downloaded in a previous run (e.g. extraction was
        // interrupted mid-way), skip the download phase entirely and go straight to extraction.
        val archiveAlreadyDownloaded = prefs.getBoolean(PREF_ARCHIVE_DOWNLOADED, false)
            && archiveFile.exists() && archiveFile.length() > 0

        if (archiveAlreadyDownloaded) {
            Log.d(TAG, "Archive already downloaded (${archiveFile.length()} bytes) — skipping download phase")
            // Clean up any partially extracted directories from the previous interrupted run.
            romsDir.listFiles()
                ?.filter { !it.name.startsWith(archiveFile.name) }
                ?.forEach { it.deleteRecursively() }
            onProgress(RomsDownloadWork.PHASE_DOWNLOADING, 1f)
        } else {
            // Delete previously extracted content but keep any partial archive and any
            // parallel-segment temp files (.seg0, .seg1, …) so downloads can resume.
            romsDir.listFiles()
                ?.filter { !it.name.startsWith(archiveFile.name) }
                ?.forEach { it.deleteRecursively() }

            val downloadUrl = "https://huggingface.co/datasets/Emuladores/sets/resolve/main/romssnesnds.7z?download=true"
            Log.d(TAG, "Starting download")
            downloadFileParallel(downloadUrl, archiveFile) { progress ->
                onProgress(RomsDownloadWork.PHASE_DOWNLOADING, progress)
            }
            // Mark the archive as fully downloaded so that if extraction is interrupted,
            // the next run can skip straight to the extraction phase.
            prefs.edit().putBoolean(PREF_ARCHIVE_DOWNLOADED, true).apply()
        }

        Log.d(TAG, "archiveFile size=${archiveFile.length()}, extracting")
        onProgress(RomsDownloadWork.PHASE_EXTRACTING, 0f)
        try {
            extractSevenZ(archiveFile, romsDir) { progress ->
                onProgress(RomsDownloadWork.PHASE_EXTRACTING, progress)  // suspend call OK: lambda is suspend
            }
        } catch (e: CancellationException) {
            // Preserve the archive file and PREF_ARCHIVE_DOWNLOADED so the next run
            // can resume extraction without re-downloading the archive.
            throw e
        } catch (e: Exception) {
            // Extraction failed (archive may be corrupt or truncated).
            // Clear the flag and delete the archive so the next retry starts a
            // fresh download instead of looping forever on the same bad file.
            Log.e(TAG, "Extraction failed — deleting archive to force re-download on next retry", e)
            archiveFile.delete()
            prefs.edit().putBoolean(PREF_ARCHIVE_DOWNLOADED, false).apply()
            throw e
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
                    onProgress(RomsDownloadWork.PHASE_EXTRACTING, 0.99f + 0.01f * (index + 1) / extractedFiles.size)
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
            .putBoolean(PREF_DOWNLOAD_STARTED, false)
            .putBoolean(PREF_ARCHIVE_DOWNLOADED, false)
            .putInt(PREF_EXTRACTION_VERSION, EXTRACTION_VERSION)
            .apply()
        Log.d(TAG, "Download and extraction complete")
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
            // Allow up to 8 concurrent connections to the same host so all 4 parallel
            // segments (plus retries) never queue waiting for a free slot.
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
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

    private suspend fun downloadFile(url: String, destination: File, onProgress: suspend (Float) -> Unit) {
        val maxRetries = 5
        var attempt = 0
        while (true) {
            attempt++
            val existingBytes = if (destination.exists()) destination.length() else 0L
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android) LemuroidApp/1.0")
                .apply { if (existingBytes > 0) header("Range", "bytes=$existingBytes-") }
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    Log.d(TAG, "HTTP ${response.code} for $url (attempt $attempt, offset $existingBytes)")
                    if (response.code == 416) {
                        // Range Not Satisfiable — file is already fully downloaded
                        Log.d(TAG, "File already complete (416), skipping")
                        onProgress(1f)
                        return
                    }
                    if (!response.isSuccessful) throw IOException("HTTP error ${response.code}: ${response.message}")
                    val body = response.body ?: throw IOException("Empty response body")
                    val isResume = response.code == 206
                    val contentLength = body.contentLength()
                    val totalLength = if (isResume && contentLength > 0) existingBytes + contentLength else contentLength
                    destination.parentFile?.mkdirs()
                    // Append when resuming; truncate when server doesn't support Range (200)
                    FileOutputStream(destination, isResume).use { outputStream ->
                        body.byteStream().use { inputStream ->
                            val buffer = ByteArray(256 * 1024)
                            var bytesWritten = if (isResume) existingBytes else 0L
                            var n: Int
                            while (inputStream.read(buffer).also { n = it } != -1) {
                                outputStream.write(buffer, 0, n)
                                bytesWritten += n
                                if (totalLength > 0) onProgress((bytesWritten.toFloat() / totalLength).coerceAtMost(1f))
                            }
                        }
                    }
                }
                Log.d(TAG, "Downloaded ${destination.name}: ${destination.length()} bytes")
                return // success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download attempt $attempt failed: ${e.message}")
                if (attempt >= maxRetries) throw IOException("Download failed after $maxRetries attempts: ${e.message}", e)
                val delayMs = (attempt * 5_000L).coerceAtMost(30_000L)
                Log.d(TAG, "Retrying in ${delayMs}ms (attempt ${attempt + 1}/$maxRetries)...")
                delay(delayMs)
            }
        }
    }

    /**
     * Downloads [url] to [destination] using [numSegments] parallel HTTP range requests,
     * similar to aria2c. Falls back to single-connection [downloadFile] if the server
     * does not report a Content-Length or does not announce byte-range support.
     *
     * Resume support: each segment's bytes are stored in a temporary side-car file
     * (`destination.seg0`, `.seg1`, …) that survives process restarts. The segment
     * files are concatenated into [destination] only after every segment finishes.
     */
    private suspend fun downloadFileParallel(
        url: String,
        destination: File,
        numSegments: Int = 4,
        onProgress: suspend (Float) -> Unit,
    ) {
        // Step 1: HEAD request to learn Content-Length and Accept-Ranges.
        val totalSize: Long
        val supportsRanges: Boolean
        try {
            val headRequest = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "Mozilla/5.0 (Android) LemuroidApp/1.0")
                .build()
            httpClient.newCall(headRequest).execute().use { resp ->
                totalSize = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                supportsRanges =
                    resp.header("Accept-Ranges")?.lowercase() == "bytes" && totalSize > 0L
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "HEAD request failed ($e) — falling back to single-connection download")
            downloadFile(url, destination, onProgress)
            return
        }

        if (!supportsRanges) {
            Log.d(TAG, "Server does not support byte ranges — falling back to single-connection download")
            downloadFile(url, destination, onProgress)
            return
        }

        Log.d(TAG, "Parallel download: $numSegments connections, totalSize=$totalSize bytes")

        // Step 2: Compute non-overlapping byte ranges for each segment.
        val segmentSize = totalSize / numSegments
        data class Segment(val index: Int, val start: Long, val end: Long) {
            val segFile get() = File(destination.parent, "${destination.name}.seg$index")
            val alreadyDownloaded
                get() = if (segFile.exists()) segFile.length().coerceAtMost(end - start + 1) else 0L
        }
        val segments = (0 until numSegments).map { i ->
            val start = i * segmentSize
            val end = if (i == numSegments - 1) totalSize - 1 else (i + 1) * segmentSize - 1
            Segment(i, start, end)
        }

        // Seed overall progress from any partial segment files left from a previous run.
        val totalDownloaded = AtomicLong(segments.sumOf { it.alreadyDownloaded })

        // Step 3: Download all segments in parallel.
        coroutineScope {
            segments.map { seg ->
                async {
                    downloadSegment(
                        url = url,
                        segFile = seg.segFile,
                        rangeStart = seg.start,
                        rangeEnd = seg.end,
                        alreadyDownloaded = seg.alreadyDownloaded,
                    ) { n ->
                        val done = totalDownloaded.addAndGet(n)
                        onProgress((done.toFloat() / totalSize).coerceAtMost(1f))
                    }
                }
            }.awaitAll()
        }

        // Step 4: Concatenate completed segment files into the final archive.
        Log.d(TAG, "All $numSegments segments complete — concatenating into ${destination.name}")
        destination.parentFile?.mkdirs()
        FileOutputStream(destination).use { out ->
            segments.forEach { seg -> seg.segFile.inputStream().use { it.copyTo(out) } }
        }
        segments.forEach { it.segFile.delete() }
        onProgress(1f)
        Log.d(TAG, "Parallel download complete: ${destination.length()} bytes")
    }

    /**
     * Downloads the byte range [rangeStart]..[rangeEnd] of [url] into [segFile],
     * appending to [alreadyDownloaded] bytes already present on disk.
     * Retries up to 5 times with linear backoff on any transient error.
     */
    private suspend fun downloadSegment(
        url: String,
        segFile: File,
        rangeStart: Long,
        rangeEnd: Long,
        alreadyDownloaded: Long,
        onBytesWritten: suspend (Long) -> Unit,
    ) {
        val segTotal = rangeEnd - rangeStart + 1
        if (alreadyDownloaded >= segTotal) {
            Log.d(TAG, "Segment [$rangeStart-$rangeEnd] already complete")
            return
        }
        val maxRetries = 5
        var attempt = 0
        var written = alreadyDownloaded
        while (true) {
            attempt++
            val from = rangeStart + written
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android) LemuroidApp/1.0")
                .header("Range", "bytes=$from-$rangeEnd")
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    Log.d(TAG, "Segment [$rangeStart-$rangeEnd]: HTTP ${response.code} (attempt $attempt, offset $from)")
                    if (response.code == 416) {
                        Log.d(TAG, "Segment [$rangeStart-$rangeEnd]: complete (416)")
                        return
                    }
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${response.message}")
                    val body = response.body ?: throw IOException("Empty response body")
                    FileOutputStream(segFile, written > 0).use { out ->
                        body.byteStream().use { inputStream ->
                            val buffer = ByteArray(256 * 1024)
                            var n: Int
                            while (inputStream.read(buffer).also { n = it } != -1) {
                                out.write(buffer, 0, n)
                                written += n
                                onBytesWritten(n.toLong())
                            }
                        }
                    }
                }
                Log.d(TAG, "Segment [$rangeStart-$rangeEnd] complete: $written/$segTotal bytes")
                return // success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt >= maxRetries)
                    throw IOException("Segment [$rangeStart-$rangeEnd] failed after $maxRetries attempts: ${e.message}", e)
                val delayMs = (attempt * 5_000L).coerceAtMost(30_000L)
                Log.w(TAG, "Segment [$rangeStart-$rangeEnd] attempt $attempt failed: ${e.message}, retrying in ${delayMs}ms")
                delay(delayMs)
            }
        }
    }

    private suspend fun extractSevenZ(archiveFile: File, destDir: File, onProgress: suspend (Float) -> Unit) {
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
