package com.swordfish.lemuroid.app.shared.roms

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.swordfish.lemuroid.BuildConfig
import timber.log.Timber
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import com.swordfish.lemuroid.lib.ssl.ConscryptOkHttpHelper.applyConscryptTls
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class DownloadRomsState {
    object Idle : DownloadRomsState()
    data class Downloading(val progress: Float) : DownloadRomsState()
    data class Extracting(val progress: Float) : DownloadRomsState()
    object Done : DownloadRomsState()
    data class Error(val message: String) : DownloadRomsState()
    object OutOfSpace : DownloadRomsState()
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
        private const val EXTRACTION_VERSION = 10
        private const val PREF_EXTRACTION_VERSION = "extraction_version"

        private const val HF_DATASET_OWNER = "luisluis123"
        private const val HF_DATASET_NAME = "lemusets"
        private const val HF_ROMS_PATH = "roms"
        private const val HF_API_BASE =
            "https://huggingface.co/api/datasets/$HF_DATASET_OWNER/$HF_DATASET_NAME"
        private const val HF_DOWNLOAD_BASE =
            "https://huggingface.co/datasets/$HF_DATASET_OWNER/$HF_DATASET_NAME/resolve/main"

        /** Set to true as soon as a download is enqueued; cleared on completion or cancel. */
        const val PREF_DOWNLOAD_STARTED = "download_started"

        /** Last download progress (0f–1f); saved every ~2% so the progress bar restores
         *  immediately on "Try Again" without flashing 0% while awaiting the first network byte. */
        private const val PREF_LAST_DOWNLOAD_PROGRESS = "last_download_progress"

        /**
         * All valid system dbnames. Used by unwrapWrapperFolders to avoid accidentally
         * treating an already-correctly-named folder (e.g. "md/") as a wrapper.
         */
        val SYSTEM_DBNAMES = setOf(
            "nes", "snes", "md", "gb", "gbc", "gba", "n64", "sms", "psp", "nds",
            "gg", "atari2600", "psx", "fbneo", "mame2003plus", "pce", "lynx",
            "atari7800", "atari5200", "scd", "ngp", "ngc", "ws", "wsc", "dos", "3ds", "msx", "msx2",
        )

        /**
         * Maps human-readable folder names (as they may appear inside the archive)
         * to the dbname values that Lemuroid uses to identify systems.
         * Case-insensitive matching is applied at runtime.
         */
        val FOLDER_NAME_MAP = mapOf(
            // Short folder names used in the luisluis123/lemusets dataset
            "a26"                  to "atari2600",
            "a78"                  to "atari7800",
            "a52"                  to "atari5200",
            "arcade"               to "fbneo",
            "atari 2600"           to "atari2600",
            "atari 5200"           to "atari5200",
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
            // 3DS — no human-readable alias was mapped before; add common variants.
            "3ds"                  to "3ds",
            "nintendo 3ds"         to "3ds",
            "3ds games"            to "3ds",
            // DOS — same situation.
            "dos"                  to "dos",
            "ms-dos"               to "dos",
            "dos games"            to "dos",
            // MSX / MSX2
            "msx"                  to "msx",
            "microsoft msx"        to "msx",
            "msx2"                 to "msx2",
            "msx 2"                to "msx2",
            "microsoft msx2"       to "msx2",
            "microsoft msx 2"      to "msx2",
        )
    }

    private val appContext = context.applicationContext
    // Use lazy so that SharedPreferences disk I/O is deferred until first actual use,
    // rather than blocking the main thread during ViewModel construction.
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val httpClient: OkHttpClient by lazy { buildHttpClient() }

    private val archiveAuthHeader by lazy {
        val email = BuildConfig.ARCHIVE_EMAIL
        val password = BuildConfig.ARCHIVE_PASSWORD
        if (email.isBlank() || password.isBlank()) {
            null
        } else {
            Credentials.basic(email, password)
        }
    }

    private val versionOutdated: Boolean by lazy {
        val storedVersion = prefs.getInt(PREF_EXTRACTION_VERSION, 0)
        storedVersion < EXTRACTION_VERSION
    }

    private val initialState: DownloadRomsState by lazy {
        val done = prefs.getBoolean(PREF_DOWNLOAD_DONE, false)
        if (done && !versionOutdated) DownloadRomsState.Done else {
            // Reset so the user sees the download card again and a fresh download runs.
            if (done) prefs.edit().putBoolean(PREF_DOWNLOAD_DONE, false).apply()
            DownloadRomsState.Idle
        }
    }

    init {
        // Run auto-restart logic on a background thread to avoid blocking the main thread
        // with SharedPreferences disk I/O during ViewModel creation.
        Thread {
            if (prefs.getBoolean(PREF_DOWNLOAD_STARTED, false) && !isDownloadDone()) {
                RomsDownloadWork.enqueue(appContext, replace = versionOutdated)
            }
        }.start()
    }

    // State is derived from WorkManager's WorkInfo so it stays accurate even after screen lock.
    val state: Flow<DownloadRomsState> = WorkManager.getInstance(appContext)
        .getWorkInfosForUniqueWorkFlow(RomsDownloadWork.UNIQUE_WORK_ID)
        .map { workInfos ->
            val info = workInfos.firstOrNull()
            when {
                info == null -> initialState
                info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.BLOCKED ->
                    // Show the last saved progress instead of 0% while the worker is queued,
                    // so the bar doesn't flash back to zero on "Try Again" after an error.
                    DownloadRomsState.Downloading(prefs.getFloat(PREF_LAST_DOWNLOAD_PROGRESS, 0f))
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
                    val isOutOfSpace = info.outputData.getBoolean(RomsDownloadWork.KEY_OUT_OF_SPACE, false)
                    if (isOutOfSpace) {
                        DownloadRomsState.OutOfSpace
                    } else {
                        val error = info.outputData.getString(RomsDownloadWork.KEY_ERROR) ?: "Unknown error"
                        DownloadRomsState.Error(error)
                    }
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

    suspend fun cancelDownload() {
        WorkManager.getInstance(appContext).cancelUniqueWork(RomsDownloadWork.UNIQUE_WORK_ID)
        // Delete all remnant files so the next download starts from scratch.
        // deleteRecursively() is blocking I/O — dispatch to IO to avoid blocking the caller.
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            DirectoriesManager(appContext).getInternalRomsDirectory().deleteRecursively()
        }
        prefs.edit()
            .putBoolean(PREF_DOWNLOAD_STARTED, false)
            .remove(PREF_DOWNLOAD_DONE)
            .remove(PREF_LAST_DOWNLOAD_PROGRESS)
            .apply()
        Timber.d("Download cancelled and all files deleted")
    }

    fun downloadAndExtract() {
        prefs.edit().putBoolean(PREF_DOWNLOAD_STARTED, true).apply()
        RomsDownloadWork.enqueue(appContext)
    }

    internal suspend fun doDownload(onProgress: suspend (phase: String, progress: Float) -> Unit) {
        var lastSavedProgress = prefs.getFloat(PREF_LAST_DOWNLOAD_PROGRESS, -1f)
        suspend fun reportProgress(phase: String, progress: Float) {
            onProgress(phase, progress)
            if (phase == RomsDownloadWork.PHASE_DOWNLOADING && progress - lastSavedProgress >= 0.02f) {
                lastSavedProgress = progress
                prefs.edit().putFloat(PREF_LAST_DOWNLOAD_PROGRESS, progress).apply()
            }
        }
        val romsDir = DirectoriesManager(appContext).getInternalRomsDirectory()
        romsDir.mkdirs()

        Timber.d("Fetching ROM file list from HuggingFace...")
        reportProgress(RomsDownloadWork.PHASE_DOWNLOADING, 0f)

        // ── Step 1: enumerate system folders ──────────────────────────────────
        val systemEntries = fetchHfTree(HF_ROMS_PATH).filter { it.type == "directory" }
        Timber.d("Found ${systemEntries.size} system folder(s): ${systemEntries.map { it.name }}")

        // ── Step 2: enumerate ROM files per system folder ─────────────────────
        data class RomFile(val hfPath: String, val size: Long, val destFile: File)
        data class SystemRomBatch(val dbname: String, val files: List<RomFile>)
        val batches = mutableListOf<SystemRomBatch>()
        for (entry in systemEntries) {
            val folderLower = entry.name.lowercase()
            val dbname = FOLDER_NAME_MAP[folderLower]
                ?: if (SYSTEM_DBNAMES.contains(folderLower)) folderLower else null
            if (dbname == null) {
                Timber.w("Skipping unknown system folder '${entry.name}'")
                continue
            }
            val files = fetchHfTree(entry.path).filter { it.type == "file" }
            Timber.d("  ${entry.name} -> $dbname: ${files.size} file(s)")
            val destDir = File(romsDir, dbname)
            batches.add(SystemRomBatch(
                dbname = dbname,
                files = files.map { f -> RomFile(f.path, f.size, File(destDir, f.name)) },
            ))
        }
        if (batches.all { it.files.isEmpty() }) throw IOException("No ROM files found in HuggingFace dataset")

        // ── Step 3: download each file with per-system incremental library sync ───
        val totalBytes = batches.sumOf { b -> b.files.sumOf { it.size } }.coerceAtLeast(1L)
        // Seed from already-complete files so the progress bar resumes correctly.
        var downloadedBytes = batches.sumOf { b ->
            b.files.filter { it.destFile.exists() && it.destFile.length() == it.size }.sumOf { it.size }
        }
        reportProgress(RomsDownloadWork.PHASE_DOWNLOADING, (downloadedBytes.toFloat() / totalBytes).coerceAtMost(1f))

        for (batch in batches) {
            var anyNewFile = false
            for (romFile in batch.files) {
                if (romFile.destFile.exists() && romFile.destFile.length() == romFile.size) {
                    continue // already downloaded
                }
                romFile.destFile.parentFile?.mkdirs()
                val url = "$HF_DOWNLOAD_BASE/${romFile.hfPath}"
                val bytesAtStart = downloadedBytes
                try {
                    downloadFile(url, romFile.destFile) { fileProgress ->
                        val effective = bytesAtStart + (fileProgress * romFile.size).toLong()
                        reportProgress(RomsDownloadWork.PHASE_DOWNLOADING,
                            (effective.toFloat() / totalBytes).coerceAtMost(1f))
                    }
                    downloadedBytes += romFile.size
                    anyNewFile = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Skip this file — log and continue so one bad file doesn't abort all others.
                    Timber.e(e, "Skipping ${romFile.hfPath}: ${e.message}")
                }
            }
            // Sync the library after each system so the user sees games appear incrementally,
            // even if the overall download is interrupted before all systems complete.
            if (anyNewFile) {
                LibraryIndexScheduler.scheduleLibrarySync(appContext)
            }
        }

        // ── Step 4: copy to SAF folder if the user configured one ─────────────
        val safUriStr = getLegacyPrefs().getString(
            appContext.getString(com.swordfish.lemuroid.lib.R.string.pref_key_extenral_folder), null
        )
        if (!safUriStr.isNullOrEmpty()) {
            val safTree = DocumentFile.fromTreeUri(appContext, Uri.parse(safUriStr))
            if (safTree != null && safTree.canWrite()) {
                Timber.d("Copying ROMs to SAF dir: $safUriStr")
                romsDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    copyFileToSaf(file, romsDir, safTree)
                }
                romsDir.deleteRecursively()
                romsDir.mkdirs()
                Timber.d("ROMs moved to SAF directory")
            } else {
                Timber.w("SAF tree not writable — ROMs left in internal storage")
            }
        }

        LibraryIndexScheduler.scheduleLibrarySync(appContext)
        prefs.edit()
            .putBoolean(PREF_DOWNLOAD_DONE, true)
            .putBoolean(PREF_DOWNLOAD_STARTED, false)
            .putInt(PREF_EXTRACTION_VERSION, EXTRACTION_VERSION)
            .remove(PREF_LAST_DOWNLOAD_PROGRESS)
            .apply()
        Timber.d("All ROMs downloaded successfully")
    }

    private fun getLegacyPrefs(): SharedPreferences =
        @Suppress("DEPRECATION")
        SharedPreferencesHelper.getLegacySharedPreferences(appContext)

    private fun copyFileToSaf(file: File, sourceRoot: File, destTree: DocumentFile) {
        val parts = file.relativeTo(sourceRoot).path.split(File.separator).filter { it.isNotEmpty() }
        var currentDoc = destTree
        parts.dropLast(1).forEach { dirName ->
            currentDoc = currentDoc.findFile(dirName) ?: currentDoc.createDirectory(dirName)
                ?: run { Timber.w("SAF: could not create dir '$dirName' for ${file.name}"); return }
        }
        val destFile = currentDoc.createFile("application/octet-stream", file.name)
            ?: run { Timber.w("SAF: createFile failed for ${file.name}"); return }
        val out = appContext.contentResolver.openOutputStream(destFile.uri)
            ?: run { Timber.w("SAF: openOutputStream failed for ${file.name}"); return }
        out.use { file.inputStream().use { input -> input.copyTo(it) } }
    }

    private fun buildHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .applyConscryptTls()
            .connectTimeout(30, TimeUnit.SECONDS)
            // 90 s read timeout: if the CDN stalls (stops sending data but keeps the TCP
            // connection open), OkHttp will throw an IOException after this period and the
            // retry loop will re-open a fresh ranged connection from the last written byte.
            // 0 = no timeout would cause the download to hang forever on a stalled connection.
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .authenticator { _, response ->
                val host = response.request.url.host
                if (!isArchiveHost(host)) {
                    null
                } else if (archiveAuthHeader.isNullOrBlank()) {
                    null
                } else if (response.request.header("Authorization") != null) {
                    null
                } else {
                    response.request.newBuilder()
                        .header("Authorization", archiveAuthHeader!!)
                        .build()
                }
            }
            // Allow up to 16 concurrent connections to the same host so all 8 parallel
            // segments (plus retries) never queue waiting for a free slot.
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        return builder.build()
    }

    private fun isArchiveHost(host: String): Boolean =
        host == "archive.org" || host.endsWith(".archive.org")

    // Thrown for HTTP 4xx (client) errors that should never be retried.
    private class PermanentHttpException(message: String) : IOException(message)

    // Returns a backoff delay in milliseconds for the given attempt number.
    // Exponential with jitter: 4s, 8s, 16s, 32s, 60s … capped at 60 s.
    // Fast enough to recover from a mobile network blip without hammering the server.
    private fun retryDelayMs(attempt: Int): Long {
        val base = (4_000L * (1L shl (attempt - 1).coerceAtMost(4)))
        val jitter = (Math.random() * 2_000).toLong()
        return (base + jitter).coerceAtMost(60_000L)
    }

    private suspend fun downloadFile(url: String, destination: File, onProgress: suspend (Float) -> Unit) {
        // 30 retries — enough to survive frequent connection aborts on a 5 GB mobile download.
        val maxRetries = 30
        var attempt = 0
        while (true) {
            attempt++
            val existingBytes = if (destination.exists()) destination.length() else 0L
            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android) LemuroidApp/1.0")
                .apply {
                    if (existingBytes > 0) {
                        header("Range", "bytes=$existingBytes-")
                    }
                }

            val parsedUrl = Uri.parse(url)
            val useArchiveAuth = isArchiveHost(parsedUrl.host ?: "") && !archiveAuthHeader.isNullOrBlank()
            if (useArchiveAuth) {
                requestBuilder.header("Authorization", archiveAuthHeader!!)
            }

            val request = requestBuilder.build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    Timber.d("HTTP ${response.code} for $url (attempt $attempt, offset $existingBytes)")
                    if (response.code == 416) {
                        // Range Not Satisfiable — file is already fully downloaded
                        Timber.d("File already complete (416), skipping")
                        onProgress(1f)
                        return
                    }
                    // 429 = rate-limited; treat as transient so the retry loop backs off
                    if (response.code == 429) {
                        val retryAfterSec = response.header("Retry-After")?.toLongOrNull() ?: 60L
                        throw IOException("Rate limited (429), retry after ${retryAfterSec}s")
                    }
                    // 4xx (except 416, 429) = permanent client error — fail immediately without retrying
                    if (response.code in 400..499)
                        throw PermanentHttpException("Permanent HTTP ${response.code}: ${response.message}")
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
                Timber.d("Downloaded ${destination.name}: ${destination.length()} bytes")
                return // success
            } catch (e: CancellationException) {
                throw e
            } catch (e: PermanentHttpException) {
                throw e // never retry permanent client errors
            } catch (e: Exception) {
                Timber.w("Download attempt $attempt failed (${e.javaClass.simpleName}): ${e.message}")
                if (attempt >= maxRetries) throw IOException("Download failed after $maxRetries attempts: ${e.message}", e)
                val delayMs = retryDelayMs(attempt)
                Timber.d("Retrying in ${delayMs}ms (attempt ${attempt + 1}/$maxRetries)...")
                delay(delayMs)
            }
        }
    }

    /**
     * Lists entries at [folderPath] in the HuggingFace dataset, handling pagination.
     * Retries on 429 (rate-limit) and transient network errors.
     */
    private suspend fun fetchHfTree(folderPath: String): List<HfTreeEntry> {
        // Use limit=1000 so a single page covers virtually any folder in this dataset.
        // Without an explicit limit the HF API may truncate results to a small default
        // (causing only the first few alphabetical entries to be returned).
        val baseUrl = "$HF_API_BASE/tree/main/$folderPath?limit=1000"
        val allEntries = mutableListOf<HfTreeEntry>()
        var nextUrl: String? = baseUrl
        var page = 0
        while (nextUrl != null) {
            page++
            Timber.d("fetchHfTree('$folderPath') page=$page url=$nextUrl")
            val request = Request.Builder()
                .url(nextUrl)
                .header("User-Agent", "Mozilla/5.0 (Android) LemuroidApp/1.0")
                .build()
            var resolvedNext: String? = null
            // Retry loop for this individual page request (handles 429 / transient errors).
            var pageAttempt = 0
            while (true) {
                pageAttempt++
                try {
                    httpClient.newCall(request).execute().use { response ->
                        if (response.code == 429) {
                            val retryAfterSec = response.header("Retry-After")?.toLongOrNull() ?: 60L
                            throw IOException("429 rate limited, retry after ${retryAfterSec}s")
                        }
                        if (!response.isSuccessful)
                            throw IOException("HuggingFace API error ${response.code} for /$folderPath")
                        val body = response.body?.string()
                            ?: throw IOException("Empty response from HuggingFace API for /$folderPath")
                        val parsed = parseHfTreeJson(body)
                        Timber.d("fetchHfTree('$folderPath') page=$page -> ${parsed.size} entries (dirs=${parsed.count { it.type=="directory" }}, files=${parsed.count { it.type=="file" }})")
                        allEntries.addAll(parsed)
                        // HuggingFace paginates with Link: <url>; rel="next"
                        response.header("Link")?.let { link ->
                            Timber.d("fetchHfTree('$folderPath') Link header: $link")
                            Regex("""<([^>]+)>;\s*rel="next"""").find(link)
                                ?.groupValues?.get(1)
                                ?.let { resolvedNext = it }
                        }
                    }
                    break // page fetched successfully
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    if (pageAttempt >= 5)
                        throw IOException("fetchHfTree('$folderPath') page $page failed after $pageAttempt attempts: ${e.message}", e)
                    val rateLimitMsg = e.message ?: ""
                    val delayMs = if (rateLimitMsg.startsWith("429 rate limited")) {
                        val secs = Regex("retry after (\\d+)s").find(rateLimitMsg)?.groupValues?.get(1)?.toLongOrNull() ?: 60L
                        minOf(secs * 1000L, 5 * 60_000L)
                    } else {
                        minOf(3000L * pageAttempt, 15_000L)
                    }
                    Timber.w("fetchHfTree('$folderPath') page $page attempt $pageAttempt failed: ${e.message}, retrying in ${delayMs}ms")
                    delay(delayMs)
                }
            }
            nextUrl = resolvedNext
        }
        Timber.d("fetchHfTree('$folderPath') TOTAL ${allEntries.size} entries across $page page(s)")
        return allEntries
    }

    private fun parseHfTreeJson(json: String): List<HfTreeEntry> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.getJSONObject(i)
            val path = obj.optString("path")
            if (path.isEmpty()) return@mapNotNull null
            // For LFS-tracked files the top-level "size" is the LFS pointer size (~127 bytes).
            // The actual content size is in the nested "lfs.size" field.
            val actualSize = obj.optJSONObject("lfs")?.optLong("size", 0L)
                ?: obj.optLong("size", 0L)
            HfTreeEntry(
                type = obj.optString("type"),
                path = path,
                name = obj.optString("name", path.substringAfterLast("/")),
                size = actualSize,
            )
        }
    }

    private data class HfTreeEntry(
        val type: String,  // "file" or "directory"
        val path: String,  // e.g. "roms12g/gb/Tetris.gb"
        val name: String,  // e.g. "Tetris.gb"
        val size: Long,    // bytes (0 for directories)
    )

}

