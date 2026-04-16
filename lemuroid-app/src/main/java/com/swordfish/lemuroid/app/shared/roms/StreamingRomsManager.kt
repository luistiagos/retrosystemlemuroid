package com.swordfish.lemuroid.app.shared.roms

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.lib.library.HeavySystemFilter
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import com.swordfish.lemuroid.lib.ssl.ConscryptOkHttpHelper.applyConscryptTls
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import com.swordfish.lemuroid.BuildConfig

/**
 * State for the streaming (file-by-file) ROM download provider.
 */
sealed class StreamingRomsState {
    object Idle : StreamingRomsState()
    data class Downloading(
        val progress: Float,
        val currentFile: String = "",
        val downloadedFiles: Int = 0,
        val totalFiles: Int = 0,
    ) : StreamingRomsState()
    object Paused : StreamingRomsState()
    object Done : StreamingRomsState()
    data class Error(val message: String) : StreamingRomsState()
}

/**
 * Represents a single file entry discovered from the HuggingFace tree API.
 */
data class HuggingFaceFileEntry(
    val path: String,   // e.g. "roms12g/snes/game.sfc"
    val size: Long,
    val downloadUrl: String,
)

/**
 * Downloads ROMs file-by-file from HuggingFace, placing each one directly into the
 * correct system subfolder inside the Lemuroid ROMs directory.  After each file is
 * written the library index is triggered so the user can play immediately.
 *
 * The original [RomsDownloadManager] is left untouched and can still be used as a
 * fallback by switching the download card back to it.
 */
class StreamingRomsManager(context: Context, autoRestart: Boolean = true) {

    companion object {
        private const val PREFS_NAME = "streaming_roms_prefs"
        const val PREF_DOWNLOAD_DONE = "streaming_download_done"
        const val PREF_DOWNLOAD_STARTED = "streaming_download_started"
        const val PREF_PAUSED = "streaming_download_paused"
        const val PREF_WIFI_ONLY = "wifi_only_download"
        private const val PREF_DOWNLOADED_FILES = "streaming_downloaded_files"
        private const val PREF_CATALOG_VERSION = "streaming_catalog_version"

        /**
         * Bump this whenever the embedded catalog_manifest.txt gains new systems/entries.
         * On app start the stored version is compared; if outdated, PREF_DOWNLOAD_DONE is
         * cleared so populateFromEmbeddedCatalog runs again and creates the new placeholders
         * (existing files are never deleted — only missing ones are created).
         */
        private const val CATALOG_VERSION = 3

        /**
         * Root path inside the HuggingFace dataset repository.
         * All sub-folders of this path are treated as system folders.
         */
        private const val HF_DATASET = "luisluis123/lemusets"
        private const val HF_ROOT_PATH = "roms"

        /**
         * HuggingFace API endpoint to list all files in a directory (recursive).
         * Returns a JSON array of objects with "path", "size", "type", "url".
         */
        private fun hfApiUrl(path: String = HF_ROOT_PATH): String =
            "https://huggingface.co/api/datasets/$HF_DATASET/tree/main/$path?recursive=true&limit=10000"

        /**
         * Builds the direct download URL for a given path inside the dataset.
         */
        fun hfDownloadUrl(path: String): String =
            "https://huggingface.co/datasets/$HF_DATASET/resolve/main/$path?download=true"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val httpClient: OkHttpClient by lazy { buildHttpClient() }
    // Reactive pause flag — updated immediately on pause/resume so the state flow
    // responds instantly without waiting for WorkManager to emit a new WorkInfo.
    private val _pausedFlow = MutableStateFlow(prefs.getBoolean(PREF_PAUSED, false))

    init {
        // If the embedded catalog was updated (CATALOG_VERSION bumped), reset DOWNLOAD_DONE
        // so populateFromEmbeddedCatalog runs again and creates any new placeholder files.
        // Existing ROM files are never touched — only missing entries are created.
        val storedCatalogVersion = prefs.getInt(PREF_CATALOG_VERSION, 1)
        if (storedCatalogVersion < CATALOG_VERSION) {
            prefs.edit()
                .putInt(PREF_CATALOG_VERSION, CATALOG_VERSION)
                .putBoolean(PREF_DOWNLOAD_DONE, false)
                .putBoolean(PREF_DOWNLOAD_STARTED, true)
                .apply()
            if (autoRestart && !prefs.getBoolean(PREF_PAUSED, false)) {
                StreamingRomsWork.enqueue(appContext)
            }
        }

        // If a streaming download was started but never completed (app killed mid-download),
        // automatically re-enqueue the work so it resumes from where it left off.
        // Skip auto-restart if the download was explicitly paused by the user.
        // autoRestart=false when called from StreamingRomsWork itself to avoid a redundant
        // enqueue IPC call while the work is already actively running.
        if (autoRestart
            && prefs.getBoolean(PREF_DOWNLOAD_STARTED, false)
            && !prefs.getBoolean(PREF_PAUSED, false)
            && !isDownloadDone()) {
            StreamingRomsWork.enqueue(appContext)
        }
    }

    val state: Flow<StreamingRomsState> =
        WorkManager.getInstance(appContext)
            .getWorkInfosForUniqueWorkFlow(StreamingRomsWork.UNIQUE_WORK_ID)
            .combine(_pausedFlow) { workInfos, _ ->
                val info = workInfos.firstOrNull()
                val done = prefs.getBoolean(PREF_DOWNLOAD_DONE, false)
                // Always read PREF_PAUSED from SharedPreferences, not from the _pausedFlow
                // parameter. _pausedFlow only provides the reactive trigger — its value can
                // diverge from prefs when a different StreamingRomsManager instance (e.g.
                // SettingsViewModel) calls resetForRedownload() or resumeDownload().
                // SharedPreferences is the single source of truth shared across all instances.
                val paused = prefs.getBoolean(PREF_PAUSED, false)
                when {
                    // PREF_DOWNLOAD_DONE is only written inside doStreamingDownload() on
                    // successful completion — it's safe to show Done immediately without
                    // waiting for WorkManager to also emit SUCCEEDED (which can lag 1-2s,
                    // causing a visible "Downloading → Done" flash at the end of a download).
                    done ->
                        StreamingRomsState.Done
                    paused -> StreamingRomsState.Paused
                    info == null || info.state == WorkInfo.State.CANCELLED ->
                        if (prefs.getBoolean(PREF_DOWNLOAD_STARTED, false))
                            StreamingRomsState.Downloading(0f)
                        else
                            StreamingRomsState.Idle
                    info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.BLOCKED ->
                        StreamingRomsState.Downloading(
                            progress = info.progress.getFloat(StreamingRomsWork.KEY_PROGRESS, 0f),
                            currentFile = info.progress.getString(StreamingRomsWork.KEY_CURRENT_FILE) ?: "",
                            downloadedFiles = info.progress.getInt(StreamingRomsWork.KEY_DOWNLOADED_FILES, 0),
                            totalFiles = info.progress.getInt(StreamingRomsWork.KEY_TOTAL_FILES, 0),
                        )
                    info.state == WorkInfo.State.RUNNING ->
                        StreamingRomsState.Downloading(
                            progress = info.progress.getFloat(StreamingRomsWork.KEY_PROGRESS, 0f),
                            currentFile = info.progress.getString(StreamingRomsWork.KEY_CURRENT_FILE) ?: "",
                            downloadedFiles = info.progress.getInt(StreamingRomsWork.KEY_DOWNLOADED_FILES, 0),
                            totalFiles = info.progress.getInt(StreamingRomsWork.KEY_TOTAL_FILES, 0),
                        )
                    info.state == WorkInfo.State.FAILED -> {
                        val error = info.outputData.getString(StreamingRomsWork.KEY_ERROR) ?: "Unknown error"
                        StreamingRomsState.Error(error)
                    }
                    else -> StreamingRomsState.Idle
                }
            }

    fun isDownloadDone(): Boolean = prefs.getBoolean(PREF_DOWNLOAD_DONE, false)

    fun isDownloadStarted(): Boolean = prefs.getBoolean(PREF_DOWNLOAD_STARTED, false)

    fun startDownload() {
        prefs.edit().putBoolean(PREF_DOWNLOAD_STARTED, true).apply()
        // replace=true: this is an explicit new start — cancel any stale worker so the
        // fresh run picks up the latest state (e.g. PREF_DOWNLOADED_FILES reset to 0).
        StreamingRomsWork.enqueue(appContext, replace = true)
    }

    fun clearDownloadStarted() {
        prefs.edit().putBoolean(PREF_DOWNLOAD_STARTED, false).apply()
    }

    fun cancelDownload() {
        WorkManager.getInstance(appContext).cancelUniqueWork(StreamingRomsWork.UNIQUE_WORK_ID)
        prefs.edit()
            .putBoolean(PREF_DOWNLOAD_STARTED, false)
            .remove(PREF_PAUSED)
            .apply()
        _pausedFlow.value = false
        Timber.d("Streaming download cancelled")
    }

    fun pauseDownload() {
        // Write PREF_PAUSED BEFORE cancelling work so that if the app is killed
        // between these two operations, the auto-restart in init() is still blocked.
        prefs.edit().putBoolean(PREF_PAUSED, true).apply()
        _pausedFlow.value = true
        WorkManager.getInstance(appContext).cancelUniqueWork(StreamingRomsWork.UNIQUE_WORK_ID)
        Timber.d("Streaming download paused")
    }

    fun resumeDownload() {
        prefs.edit().putBoolean(PREF_PAUSED, false).apply()
        _pausedFlow.value = false
        // replace=false (KEEP): pauseDownload() already cancelled the worker, so there is
        // nothing running to protect — KEEP simply enqueues the new work normally.
        // Using KEEP instead of REPLACE avoids a race where a rapid pause→resume sequence
        // could cancel a freshly enqueued worker before it starts.
        StreamingRomsWork.enqueue(appContext, replace = false)
        Timber.d("Streaming download resumed")
    }

    fun isCurrentlyDownloading(): Boolean =
        prefs.getBoolean(PREF_DOWNLOAD_STARTED, false) &&
            !isDownloadDone() &&
            !prefs.getBoolean(PREF_PAUSED, false)

    /**
     * Resets all download state and deletes any previously downloaded ROM files so the user
     * can start a fresh download from scratch. Called from the Settings screen "Download again"
     * option when the streaming download is in the Done state.
     */
    suspend fun resetForRedownload() {
        WorkManager.getInstance(appContext).cancelUniqueWork(StreamingRomsWork.UNIQUE_WORK_ID)
        withContext(Dispatchers.IO) {
            DirectoriesManager(appContext).getInternalRomsDirectory().deleteRecursively()
        }
        prefs.edit()
            .putBoolean(PREF_DOWNLOAD_DONE, false)
            .putBoolean(PREF_DOWNLOAD_STARTED, false)
            .remove(PREF_PAUSED)
            .remove(PREF_DOWNLOADED_FILES)
            .apply()
        _pausedFlow.value = false
        Timber.d("Streaming download state reset for re-download")
    }

    /**
     * Reads the embedded catalog manifest from assets. Returns a list of relative paths
     * like "arcade/acrobatm.zip", or null if the asset is not present.
     *
     * On weak devices, entries for heavy systems (PSP, 3DS, NDS, N64, PSX, DOS, Sega CD)
     * are excluded so their placeholder files are never created.
     */
    private fun loadCatalogFromAssets(): List<String>? {
        val excludedPrefixes = HeavySystemFilter.excludedCatalogPrefixes(HeavySystemFilter.deviceTier(appContext))
        return try {
            appContext.assets.open("catalog_manifest.txt").bufferedReader().useLines { seq ->
                seq.filter { it.isNotBlank() }
                    .filter { line -> excludedPrefixes.none { prefix -> line.startsWith(prefix) } }
                    .toList()
            }
        } catch (e: IOException) {
            Timber.w("Embedded catalog not available: ${e.message}")
            null
        }
    }

    /**
     * Creates 0-byte placeholder files for every entry in the embedded catalog.
     * No network access is required — all entries are already known at build time.
     */
    private suspend fun populateFromEmbeddedCatalog(
        paths: List<String>,
        romsDir: File,
        onProgress: suspend (Float, String, Int, Int) -> Unit,
    ) {
        val total = paths.size
        val alreadyDone = prefs.getInt(PREF_DOWNLOADED_FILES, 0)
        var doneCount = alreadyDone

        var failCount = 0
        paths.forEachIndexed { index, relativePath ->
            currentCoroutineContext().ensureActive()
            val destFile = File(romsDir, relativePath)
            if (!destFile.exists()) {
                try {
                    val parent = destFile.parentFile
                    if (parent != null && !parent.exists()) parent.mkdirs()
                    destFile.createNewFile()
                } catch (e: IOException) {
                    failCount++
                    // If too many files fail, the directory is likely broken — abort early.
                    if (failCount >= 10) {
                        throw IOException(
                            "Too many file creation failures ($failCount). Last error at: ${destFile.absolutePath}",
                            e,
                        )
                    }
                    Timber.w("Skipping ${relativePath}: ${e.message}")
                    return@forEachIndexed
                }
                doneCount++
                prefs.edit().putInt(PREF_DOWNLOADED_FILES, doneCount).apply()
                if (doneCount % 500 == 0) {
                    LibraryIndexScheduler.scheduleLibrarySync(appContext)
                }
            }
            onProgress(
                (index + 1).toFloat() / total,
                destFile.name,
                doneCount,
                total,
            )
        }

        prefs.edit()
            .putBoolean(PREF_DOWNLOAD_DONE, true)
            .putBoolean(PREF_DOWNLOAD_STARTED, false)
            .remove(PREF_DOWNLOADED_FILES)
            .apply()
        LibraryIndexScheduler.scheduleLibrarySync(appContext)
        Timber.d("Embedded catalog population complete: $doneCount files")
    }

    /**
     * Main work entry point called from [StreamingRomsWork].
     * 1. If an embedded catalog_manifest.txt asset exists, creates 0-byte placeholder
     *    files directly from it (no network required).
     * 2. Otherwise, lists all files via the HuggingFace tree API and downloads them.
     * 3. After each batch, triggers a library sync so games appear immediately.
     */
    internal suspend fun doStreamingDownload(
        onProgress: suspend (progress: Float, currentFile: String, downloaded: Int, total: Int) -> Unit,
    ) {
        var romsDir = DirectoriesManager(appContext).getInternalRomsDirectory()
        romsDir.mkdirs()

        // Fallback: if external storage is unavailable (common on cheap TV boxes),
        // use internal app storage so the catalog still works.
        if (!romsDir.exists() || !romsDir.canWrite()) {
            Timber.w("External ROMs dir not writable: ${romsDir.absolutePath}, falling back to internal storage")
            romsDir = File(appContext.filesDir, "roms")
            romsDir.mkdirs()
        }
        if (!romsDir.exists() || !romsDir.canWrite()) {
            throw IOException("Cannot create ROMs directory: ${romsDir.absolutePath}")
        }

        // Fast path: use the catalog embedded in the APK assets.
        val embeddedPaths = loadCatalogFromAssets()
        if (embeddedPaths != null) {
            Timber.d("Using embedded catalog: ${embeddedPaths.size} entries")
            populateFromEmbeddedCatalog(embeddedPaths, romsDir, onProgress)
            return
        }

        Timber.d("Fetching file list from HuggingFace...")
        // Retry fetchFileList on transient network errors (e.g. UnknownHostException right after
        // a network switch from WiFi to mobile — DNS may not be ready yet).
        var fetchAttempt = 0
        var fetchResult: List<HuggingFaceFileEntry>? = null
        while (fetchResult == null) {
            fetchAttempt++
            try {
                fetchResult = fetchFileList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (fetchAttempt >= 5) throw IOException("Failed to fetch file list after $fetchAttempt attempts: ${e.message}", e)
                Timber.w("fetchFileList attempt $fetchAttempt failed: ${e.message}, retrying in ${3000L * fetchAttempt}ms")
                delay(minOf(3000L * fetchAttempt, 15000L))
            }
        }
        val files = fetchResult
        if (files.isEmpty()) throw IOException("No files found at $HF_ROOT_PATH")

        Timber.d("Found ${files.size} files to download")
        val alreadyDownloaded = prefs.getInt(PREF_DOWNLOADED_FILES, 0)
        var downloadedCount = alreadyDownloaded

        files.forEachIndexed { index, entry ->
            // Skip files already fully downloaded in a previous run
            val destFile = resolveDestinationFile(entry, romsDir)
            if (destFile.exists() && (entry.size == 0L || destFile.length() == entry.size)) {
                Timber.d("Skipping already downloaded: ${entry.path}")
                if (index >= alreadyDownloaded) {
                    downloadedCount++
                    prefs.edit().putInt(PREF_DOWNLOADED_FILES, downloadedCount).apply()
                }
                onProgress(
                    (index + 1).toFloat() / files.size,
                    destFile.name,
                    downloadedCount,
                    files.size,
                )
                return@forEachIndexed
            }

            val fileName = entry.path.substringAfterLast("/")
            Timber.d("Downloading [${ index + 1}/${files.size}]: $fileName")
            onProgress(index.toFloat() / files.size, fileName, downloadedCount, files.size)

            destFile.parentFile?.mkdirs()
            try {
                if (entry.size == 0L) {
                    // HuggingFace catalog placeholder: 0-byte file means "ROM available for
                    // on-demand download". Create locally without an HTTP round-trip.
                    destFile.createNewFile()
                } else {
                    downloadFileWithResume(entry.downloadUrl, destFile)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Skipping ${entry.path}: ${e.message}")
                return@forEachIndexed
            }

            downloadedCount++
            prefs.edit().putInt(PREF_DOWNLOADED_FILES, downloadedCount).apply()

            // Trigger library index every 100 files instead of every single file.
            // Calling scheduleLibrarySync after each of 23,000+ files floods WorkManager
            // and causes ANRs due to main-thread contention and DB write storms.
            if (downloadedCount % 100 == 0) {
                LibraryIndexScheduler.scheduleLibrarySync(appContext)
            }

            onProgress(
                (index + 1).toFloat() / files.size,
                fileName,
                downloadedCount,
                files.size,
            )
        }

        prefs.edit()
            .putBoolean(PREF_DOWNLOAD_DONE, true)
            .putBoolean(PREF_DOWNLOAD_STARTED, false)
            .remove(PREF_DOWNLOADED_FILES)
            .apply()
        // Final sync so all games appear after the download completes.
        LibraryIndexScheduler.scheduleLibrarySync(appContext)
        Timber.d("Streaming download complete: $downloadedCount files")
    }

    /**
     * Resolves the destination [File] for a HuggingFace entry.
     * The path inside the dataset is e.g. "roms12g/snes/game.sfc".
     * We strip the root prefix and place the file under [romsDir].
     */
    private fun resolveDestinationFile(entry: HuggingFaceFileEntry, romsDir: File): File {
        // Strip the HF_ROOT_PATH prefix: "roms12g/snes/game.sfc" → "snes/game.sfc"
        val relativePath = entry.path.removePrefix("$HF_ROOT_PATH/")
        return File(romsDir, relativePath)
    }

    /**
     * Fetches the recursive file list from the HuggingFace tree API with pagination.
     * Returns only file entries (type == "file"), excluding directories.
     * Uses limit=10000 to cover the entire dataset in a single page; follows
     * Link: <url>; rel="next" headers if additional pages exist.
     * Retries on 429 (rate-limit) and transient network errors.
     */
    private suspend fun fetchFileList(): List<HuggingFaceFileEntry> {
        val allFiles = mutableListOf<HuggingFaceFileEntry>()
        var nextUrl: String? = hfApiUrl()
        var page = 0
        while (nextUrl != null) {
            page++
            Timber.d("fetchFileList() page=$page url=$nextUrl")
            val request = Request.Builder()
                .url(nextUrl)
                .header("User-Agent", "Mozilla/5.0 (Android) LemuroidApp/1.0")
                .build()
            var resolvedNext: String? = null
            var pageAttempt = 0
            while (true) {
                pageAttempt++
                try {
                    httpClient.newCall(request).execute().use { response ->
                        if (response.code == 429) {
                            val retryAfterSec = response.header("Retry-After")?.toLongOrNull() ?: 60L
                            throw IOException("429 rate limited, retry after ${retryAfterSec}s")
                        }
                        if (!response.isSuccessful) throw IOException("HuggingFace API error ${response.code}: ${response.message}")
                        val body = response.body?.string() ?: throw IOException("Empty response from HuggingFace API")
                        allFiles.addAll(parseFileList(body))
                        // Follow pagination via Link header
                        response.header("Link")?.let { link ->
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
                        throw IOException("fetchFileList() page $page failed after $pageAttempt attempts: ${e.message}", e)
                    val rateLimitMsg = e.message ?: ""
                    val delayMs = if (rateLimitMsg.startsWith("429 rate limited")) {
                        val secs = Regex("retry after (\\d+)s").find(rateLimitMsg)?.groupValues?.get(1)?.toLongOrNull() ?: 60L
                        minOf(secs * 1000L, 5 * 60_000L)
                    } else {
                        minOf(3000L * pageAttempt, 15_000L)
                    }
                    Timber.w("fetchFileList() page $page attempt $pageAttempt failed: ${e.message}, retrying in ${delayMs}ms")
                    delay(delayMs)
                }
            }
            nextUrl = resolvedNext
        }
        Timber.d("fetchFileList() TOTAL ${allFiles.size} files across $page page(s)")
        return allFiles
    }

    /**
     * Parses the JSON array returned by the HuggingFace tree API.
     * Each entry looks like:
     * {"type":"file","oid":"...","size":12345,"path":"roms12g/snes/game.sfc","url":"https://..."}
     */
    private fun parseFileList(json: String): List<HuggingFaceFileEntry> {
        val result = mutableListOf<HuggingFaceFileEntry>()
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            if (obj.optString("type") != "file") continue
            val path = obj.getString("path")
            // For LFS-tracked files the top-level "size" is the LFS pointer size (~127 bytes).
            // The actual content size is in the nested "lfs.size" field.
            val size = obj.optJSONObject("lfs")?.optLong("size", 0L)
                ?: obj.optLong("size", 0L)
            // Prefer the "lfs" download URL if available, fall back to constructed URL
            val downloadUrl = hfDownloadUrl(path)
            result.add(HuggingFaceFileEntry(path, size, downloadUrl))
        }
        return result
    }

    /**
     * Downloads [url] to [destFile], resuming from where it left off if the file
     * already exists partially on disk.
     */
    private suspend fun downloadFileWithResume(url: String, destFile: File) {
        val maxRetries = 10
        var attempt = 0
        while (true) {
            attempt++
            // Re-read the file size on every attempt so the Range header is correct even
            // when a previous attempt wrote some bytes before failing.
            val existingBytes = if (destFile.exists()) destFile.length() else 0L
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android) LemuroidApp/1.0")
                .apply { if (existingBytes > 0) header("Range", "bytes=$existingBytes-") }
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (response.code == 416) {
                        Timber.d("File already complete (416): ${destFile.name}")
                        return
                    }
                    if (response.code == 429) {
                        val retryAfterSec = response.header("Retry-After")?.toLongOrNull() ?: 60L
                        throw IOException("429 rate limited, retry after ${retryAfterSec}s")
                    }
                    if (response.code in 400..499)
                        throw IOException("Permanent HTTP ${response.code}: ${response.message}")
                    if (!response.isSuccessful)
                        throw IOException("HTTP ${response.code}: ${response.message}")
                    val body = response.body ?: throw IOException("Empty body for ${destFile.name}")
                    val isResume = response.code == 206
                    destFile.parentFile?.mkdirs()
                    FileOutputStream(destFile, isResume).use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(256 * 1024)
                            var n: Int
                            while (input.read(buffer).also { n = it } != -1) {
                                out.write(buffer, 0, n)
                                // Cancellation checkpoint: allows pause/cancel to take effect
                                // immediately during large-file downloads without waiting for
                                // the entire file to finish writing.
                                currentCoroutineContext().ensureActive()
                            }
                        }
                    }
                }
                return // success
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (e.message?.startsWith("Permanent") == true) throw e
                if (attempt >= maxRetries) throw IOException("Failed after $maxRetries attempts: ${e.message}", e)
                val rateLimitMsg = e.message ?: ""
                val delayMs = if (rateLimitMsg.startsWith("429 rate limited")) {
                    val secs = Regex("retry after (\\d+)s").find(rateLimitMsg)?.groupValues?.get(1)?.toLongOrNull() ?: 60L
                    minOf(secs * 1000L, 5 * 60_000L)
                } else {
                    minOf(2000L * attempt, 10000L)
                }
                Timber.w("Retry $attempt for ${destFile.name}: ${e.message}, waiting ${delayMs}ms")
                delay(delayMs)
            }
        }
    }

    private fun buildHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .applyConscryptTls()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
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
}
