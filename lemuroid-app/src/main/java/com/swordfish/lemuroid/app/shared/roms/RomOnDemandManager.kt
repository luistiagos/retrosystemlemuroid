package com.swordfish.lemuroid.app.shared.roms

import android.content.Context
import android.net.Uri
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.lib.library.db.dao.DownloadedRomDao
import com.swordfish.lemuroid.lib.library.db.entity.DownloadedRom
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import com.swordfish.lemuroid.lib.ssl.ConscryptOkHttpHelper.applyConscryptTls
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Handles on-demand downloading of a single ROM.
 *
 * Flow:
 * 1. Map the game's systemId to the pythonanywhere endpoint system name.
 * 2. Call the find_by_file endpoint to get the actual ROM download URL.
 * 3. Download the ROM file, replacing the 0-byte placeholder on disk.
 * 4. Verify the downloaded file is non-empty.
 * 5. Record the download in [DownloadedRomDao].
 * 6. Schedule a library sync so the game is re-indexed immediately.
 */
class RomOnDemandManager(
    private val context: Context,
    private val downloadedRomDao: DownloadedRomDao,
    private val directoriesManager: DirectoriesManager,
) {
    sealed class DownloadResult {
        object Success : DownloadResult()
        data class NotFound(val fileName: String) : DownloadResult()
        data class Failure(val message: String) : DownloadResult()
    }

    companion object {
        private const val FIND_BY_FILE_ENDPOINT =
            "https://emuladores.pythonanywhere.com/find_by_file"
        private const val HUGGINGFACE_BASE =
            "https://huggingface.co/datasets/luistiagos/roms/resolve/main"
    }

    private val _pausedFlow = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _pausedFlow.asStateFlow()

    @Volatile private var activeCall: Call? = null

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .applyConscryptTls()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun pauseDownload() {
        _pausedFlow.value = true
    }

    fun resumeDownload() {
        _pausedFlow.value = false
    }

    /** Cancels the active OkHttp call so blocking IO is interrupted immediately. */
    fun cancelActiveDownload() {
        activeCall?.cancel()
        activeCall = null
        _pausedFlow.value = false
    }

    /**
     * Downloads the ROM for [game] and reports byte-level progress via [onProgress] (0.0–1.0).
     * Supports pause/resume via [pauseDownload]/[resumeDownload] and cancellation via
     * [cancelActiveDownload] or coroutine cancellation.
     */
    suspend fun downloadRom(
        game: Game,
        onProgress: (Float) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        // Reset pause for each new download.
        _pausedFlow.value = false

        val endpointSystem = RomSystemMapper.toEndpointSystem(game.systemId)
        if (endpointSystem == null) {
            Timber.w("No endpoint mapping for systemId=${game.systemId}")
            return@withContext DownloadResult.NotFound(game.fileName)
        }

        val findUrl = "$FIND_BY_FILE_ENDPOINT" +
            "?path=${Uri.encode(game.fileName)}&source_id=1&system=${Uri.encode(endpointSystem)}"
        Timber.d("On-demand lookup: $findUrl")

        val downloadUrl = try {
            resolveDownloadUrl(findUrl)
        } catch (e: IOException) {
            Timber.e(e, "Lookup failed for ${game.fileName}, will try HuggingFace fallback: ${e.message}")
            null
        }

        val finalUrl = downloadUrl ?: run {
            Timber.d("Endpoint returned no result, falling back to HuggingFace direct URL")
            buildHuggingFaceUrl(endpointSystem, game.fileName)
        }
        Timber.d("On-demand download URL for ${game.fileName}: $finalUrl")

        val destFile = resolveDestFile(game)
        try {
            downloadToFile(finalUrl, destFile, onProgress)
        } catch (e: CancellationException) {
            // Restore 0-byte placeholder on cancellation so the game remains in the catalog.
            runCatching { FileOutputStream(destFile, false).close() }
            throw e
        } catch (e: IOException) {
            Timber.e(e, "Failed to download ROM: ${game.fileName}")
            return@withContext DownloadResult.Failure(e.message ?: "Unknown IO error")
        }

        val downloadedSize = destFile.length()
        if (downloadedSize == 0L) {
            return@withContext DownloadResult.NotFound(game.fileName)
        }

        downloadedRomDao.insert(
            DownloadedRom(
                fileName = game.fileName,
                fileSize = downloadedSize,
            ),
        )

        LibraryIndexScheduler.scheduleLibrarySync(context)

        Timber.d("ROM downloaded successfully: ${game.fileName} ($downloadedSize bytes)")
        DownloadResult.Success
    }

    /**
     * Calls the find_by_file endpoint and returns the ROM download URL as plain text,
     * or null if the ROM is not found (HTTP 404 or empty body).
     * Retries up to 3 times on 429 (rate limit), respecting the Retry-After header.
     * Throws [IOException] for other HTTP errors or network failures.
     */
    private suspend fun resolveDownloadUrl(findUrl: String): String? {
        for (attempt in 1..3) {
            val request = Request.Builder()
                .url(findUrl)
                .header("User-Agent", "Mozilla/5.0 (Android) LemuroidApp/1.0")
                .build()
            var retryAfterMs: Long? = null
            try {
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.code == 404 -> return null
                        response.code == 429 -> {
                            val retryAfterSec = response.header("Retry-After")?.toLongOrNull() ?: 60L
                            retryAfterMs = minOf(retryAfterSec * 1000L, 5 * 60_000L)
                            Timber.w("Lookup rate limited (429), attempt $attempt, retry after ${retryAfterSec}s")
                        }
                        !response.isSuccessful -> throw IOException("Lookup HTTP ${response.code}: ${response.message}")
                        else -> {
                            val url = response.body?.string()?.trim()
                            return if (url.isNullOrEmpty()) null else url
                        }
                    }
                }
            } catch (e: IOException) {
                // Retry on transient network errors or non-success HTTP responses.
                // Without this catch, any UnknownHostException / SocketTimeoutException
                // propagates immediately instead of being retried.
                Timber.w("Lookup attempt $attempt failed: ${e.message}")
                if (attempt >= 3) throw IOException("Lookup failed after 3 attempts: ${e.message}", e)
                // Use a short backoff for network errors (not 429), unless retryAfterMs is already set.
                retryAfterMs = retryAfterMs ?: minOf(3_000L * attempt, 15_000L)
            }
            if (attempt < 3) delay(retryAfterMs ?: 60_000L)
        }
        throw IOException("Rate limited (429): please try again later")
    }

    /**
     * Builds a direct HuggingFace download URL for the given system and filename.
     * Used as a fallback when the pythonanywhere endpoint does not have the system indexed.
     */
    private fun buildHuggingFaceUrl(endpointSystem: String, fileName: String): String {
        val encodedName = Uri.encode(fileName)
        return "$HUGGINGFACE_BASE/$endpointSystem/$encodedName"
    }

    /**
     * Deletes the downloaded ROM (replacing it with a 0-byte placeholder) and removes
     * the [DownloadedRom] record from the database.
     */
    suspend fun deleteRom(game: Game): Unit = withContext(Dispatchers.IO) {
        val destFile = resolveDestFile(game)
        if (destFile.exists()) {
            FileOutputStream(destFile, false).use { }
        }
        downloadedRomDao.deleteByFileName(game.fileName)
        LibraryIndexScheduler.scheduleLibrarySync(context)
    }

    private fun resolveDestFile(game: Game): File {
        val uri = Uri.parse(game.fileUri)
        val path = uri.path ?: run {
            val systemDir = File(directoriesManager.getInternalRomsDirectory(), game.systemId)
            return File(systemDir, game.fileName)
        }
        return File(path)
    }

    private suspend fun downloadToFile(url: String, destFile: File, onProgress: (Float) -> Unit) {
        val maxAttempts = 5
        for (attempt in 1..maxAttempts) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android) LemuroidApp/1.0")
                .build()

            val call = httpClient.newCall(request)
            activeCall = call

            var retryAfterMs: Long? = null
            try {
                call.execute().use { response ->
                    when {
                        response.code == 429 -> {
                            val retryAfterSec = response.header("Retry-After")?.toLongOrNull() ?: 60L
                            retryAfterMs = minOf(retryAfterSec * 1000L, 5 * 60_000L)
                            Timber.w("downloadToFile 429, attempt $attempt/$maxAttempts, retry after ${retryAfterSec}s")
                        }
                        !response.isSuccessful -> throw IOException("HTTP ${response.code}: ${response.message}")
                        else -> {
                            val body = response.body ?: throw IOException("Empty response body")
                            val contentLength = body.contentLength()

                            destFile.parentFile?.mkdirs()
                            var bytesWritten = 0L

                            FileOutputStream(destFile, false).use { out ->
                                body.byteStream().use { input ->
                                    val buffer = ByteArray(256 * 1024)
                                    var n: Int
                                    while (input.read(buffer).also { n = it } != -1) {
                                        out.write(buffer, 0, n)
                                        bytesWritten += n
                                        if (contentLength > 0) {
                                            onProgress(bytesWritten.toFloat() / contentLength.toFloat())
                                        }
                                        // Cancellation checkpoint (each buffer chunk).
                                        currentCoroutineContext().ensureActive()
                                        // Pause checkpoint: suspends the coroutine until resumed.
                                        if (_pausedFlow.value) _pausedFlow.first { !it }
                                    }
                                }
                            }
                            onProgress(1f)
                            return // success
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // Retry on network failures (connection drop, timeout, bad HTTP status other than 429).
                // Without this catch, any transient network error causes immediate failure even though
                // the loop was designed to retry.
                Timber.w("downloadToFile attempt $attempt/$maxAttempts failed: ${e.message}")
                if (attempt >= maxAttempts) throw IOException("Download failed after $maxAttempts attempts: ${e.message}", e)
                val ioDelayMs = minOf(5_000L * attempt, 30_000L)
                Timber.d("Retrying downloadToFile in ${ioDelayMs}ms")
                delay(ioDelayMs)
                // `continue` skips the 429-specific delay code below and goes to the next attempt.
                continue
            } finally {
                activeCall = null
            }

            // Reached here only on 429 (IOException path uses `continue` above) — delay then retry.
            if (attempt >= maxAttempts) throw IOException("Rate limited (429). Aguarde e tente novamente.")
            delay(retryAfterMs ?: 60_000L)
        }
        throw IOException("Rate limited (429). Aguarde e tente novamente.")
    }
}

