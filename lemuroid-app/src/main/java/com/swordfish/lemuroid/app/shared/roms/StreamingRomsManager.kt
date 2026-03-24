package com.swordfish.lemuroid.app.shared.roms

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
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
class StreamingRomsManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "streaming_roms_prefs"
        const val PREF_DOWNLOAD_DONE = "streaming_download_done"
        const val PREF_DOWNLOAD_STARTED = "streaming_download_started"
        private const val PREF_DOWNLOADED_FILES = "streaming_downloaded_files"

        /**
         * Root path inside the HuggingFace dataset repository.
         * All sub-folders of this path are treated as system folders.
         */
        private const val HF_DATASET = "luisluis123/lemusets"
        private const val HF_ROOT_PATH = "roms12g"

        /**
         * HuggingFace API endpoint to list all files in a directory (recursive).
         * Returns a JSON array of objects with "path", "size", "type", "url".
         */
        private fun hfApiUrl(path: String = HF_ROOT_PATH): String =
            "https://huggingface.co/api/datasets/$HF_DATASET/tree/main/$path?recursive=true"

        /**
         * Builds the direct download URL for a given path inside the dataset.
         */
        fun hfDownloadUrl(path: String): String =
            "https://huggingface.co/datasets/$HF_DATASET/resolve/main/$path?download=true"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val httpClient: OkHttpClient by lazy { buildHttpClient() }

    val state: Flow<StreamingRomsState> = WorkManager.getInstance(appContext)
        .getWorkInfosForUniqueWorkFlow(StreamingRomsWork.UNIQUE_WORK_ID)
        .map { workInfos ->
            val info = workInfos.firstOrNull()
            val done = prefs.getBoolean(PREF_DOWNLOAD_DONE, false)
            when {
                done && (info == null || info.state == WorkInfo.State.SUCCEEDED) ->
                    StreamingRomsState.Done
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
                info.state == WorkInfo.State.SUCCEEDED -> StreamingRomsState.Done
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
        StreamingRomsWork.enqueue(appContext)
    }

    fun clearDownloadStarted() {
        prefs.edit().putBoolean(PREF_DOWNLOAD_STARTED, false).apply()
    }

    suspend fun cancelDownload() {
        WorkManager.getInstance(appContext).cancelUniqueWork(StreamingRomsWork.UNIQUE_WORK_ID)
        prefs.edit()
            .putBoolean(PREF_DOWNLOAD_STARTED, false)
            .apply()
        Timber.d("Streaming download cancelled")
    }

    /**
     * Main work entry point called from [StreamingRomsWork].
     * 1. Lists all files via the HuggingFace tree API.
     * 2. Downloads each file individually.
     * 3. After each file, triggers a library sync so games appear immediately.
     */
    internal suspend fun doStreamingDownload(
        onProgress: suspend (progress: Float, currentFile: String, downloaded: Int, total: Int) -> Unit,
    ) {
        val romsDir = DirectoriesManager(appContext).getInternalRomsDirectory()
        romsDir.mkdirs()

        Timber.d("Fetching file list from HuggingFace...")
        val files = fetchFileList()
        if (files.isEmpty()) throw IOException("No files found at $HF_ROOT_PATH")

        Timber.d("Found ${files.size} files to download")
        val alreadyDownloaded = prefs.getInt(PREF_DOWNLOADED_FILES, 0)
        var downloadedCount = alreadyDownloaded

        files.forEachIndexed { index, entry ->
            // Skip files already downloaded in a previous run
            val destFile = resolveDestinationFile(entry, romsDir)
            if (destFile.exists() && destFile.length() > 0) {
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
            downloadFileWithResume(entry.downloadUrl, destFile)

            downloadedCount++
            prefs.edit().putInt(PREF_DOWNLOADED_FILES, downloadedCount).apply()

            // Trigger library index after each file so games appear immediately
            LibraryIndexScheduler.scheduleLibrarySync(appContext)

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
     * Fetches the recursive file list from the HuggingFace tree API.
     * Returns only file entries (type == "file"), excluding directories.
     */
    private fun fetchFileList(): List<HuggingFaceFileEntry> {
        val url = hfApiUrl()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) LemuroidApp/1.0")
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HuggingFace API error ${response.code}: ${response.message}")
            val body = response.body?.string() ?: throw IOException("Empty response from HuggingFace API")
            parseFileList(body)
        }
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
            val size = obj.optLong("size", 0L)
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
    private fun downloadFileWithResume(url: String, destFile: File) {
        val existingBytes = if (destFile.exists()) destFile.length() else 0L
        val maxRetries = 10
        var attempt = 0
        while (true) {
            attempt++
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
                Timber.w("Retry $attempt for ${destFile.name}: ${e.message}")
                Thread.sleep(minOf(2000L * attempt, 10000L))
            }
        }
    }

    private fun buildHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
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
