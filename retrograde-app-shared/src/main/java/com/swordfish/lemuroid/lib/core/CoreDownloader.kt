package com.swordfish.lemuroid.lib.core

import android.content.Context
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import com.swordfish.lemuroid.lib.util.AbiUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.swordfish.lemuroid.lib.ssl.ConscryptOkHttpHelper.applyConscryptTls
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Standalone core downloader that works in any process (including :game).
 * No WorkManager, no broadcast — just a direct OkHttp download.
 */
object CoreDownloader {

    const val CORES_VERSION = "1.17.0"
    private const val CORES_BASE_URL = "https://raw.githubusercontent.com/luistiagos/libretrocores/"
    private const val MIN_VALID_CORE_SIZE = 100 * 1024L

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .applyConscryptTls()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Downloads a single core directly. Returns the downloaded [File] on success.
     * Throws on permanent failure.
     */
    suspend fun downloadCore(context: Context, coreID: CoreID): File {
        val processAbi = AbiUtils.getProcessAbi(context)
        val directoriesManager = DirectoriesManager(context)
        val mainCoresDir = directoriesManager.getCoresDirectory()
        val coresDir = File(mainCoresDir, CORES_VERSION).apply { mkdirs() }
        val libFileName = coreID.libretroFileName
        val destFile = File(coresDir, libFileName)

        // Skip if file already exists and is valid.
        if (destFile.exists()
            && destFile.length() > MIN_VALID_CORE_SIZE
            && AbiUtils.isElfCompatible(destFile, processAbi)
        ) {
            Timber.d("CoreDownloader: $libFileName already valid, skipping download")
            destFile.setExecutable(true, true)
            return destFile
        }

        if (destFile.exists()) {
            Timber.w("CoreDownloader: $libFileName exists but invalid, re-downloading")
            if (!destFile.delete()) {
                Timber.e("CoreDownloader: failed to delete invalid core file: $destFile")
            }
        }

        val url = "$CORES_BASE_URL$CORES_VERSION/lemuroid_core_${coreID.coreName}" +
            "/src/main/jniLibs/$processAbi/$libFileName"

        Timber.i("CoreDownloader: downloading $url")
        downloadWithResume(url, destFile)
        destFile.setExecutable(true, true)
        Timber.i("CoreDownloader: $libFileName ready (${destFile.length()} bytes)")
        return destFile
    }

    private suspend fun downloadWithResume(url: String, destFile: File) {
        val maxRetries = 10
        var attempt = 0
        while (true) {
            attempt++
            val existingBytes = if (destFile.exists()) destFile.length() else 0L
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "LemuroidApp/1.0")
                .apply { if (existingBytes > 0) header("Range", "bytes=$existingBytes-") }
                .build()
            try {
                withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute().use { response ->
                        if (response.code == 416) return@withContext
                        if (response.code in 400..499) throw IOException("Permanent HTTP ${response.code}")
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val isResume = response.code == 206
                        val body = response.body ?: throw IOException("Empty body")
                        FileOutputStream(destFile, isResume).use { out ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(256 * 1024)
                                var n: Int
                                while (input.read(buffer).also { n = it } != -1) {
                                    out.write(buffer, 0, n)
                                    currentCoroutineContext().ensureActive()
                                }
                            }
                        }
                    }
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (e.message?.startsWith("Permanent") == true) throw e
                if (attempt >= maxRetries) throw IOException("Download failed after $maxRetries attempts", e)
                Timber.w("CoreDownloader attempt $attempt failed: ${e.message}, retrying...")
                delay(minOf(2000L * attempt, 10000L))
            }
        }
    }
}
