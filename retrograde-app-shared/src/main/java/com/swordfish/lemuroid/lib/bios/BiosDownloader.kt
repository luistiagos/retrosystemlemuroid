package com.swordfish.lemuroid.lib.bios

import android.content.Context
import com.swordfish.lemuroid.lib.ssl.ConscryptOkHttpHelper.applyConscryptTls
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object BiosDownloader {

    private const val BASE_URL =
        "https://huggingface.co/datasets/luistiagos/bios/resolve/main"
    private const val MAX_RETRIES = 5

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
     * Downloads every file in [missingFileNames] from HuggingFace into the system directory.
     * Verifies the MD5 of each downloaded file against [BiosManager.SUPPORTED_BIOS].
     * Throws on permanent failure (HTTP 4xx or MD5 mismatch after download).
     */
    suspend fun downloadMissing(context: Context, missingFileNames: List<String>) {
        val systemDir = DirectoriesManager(context).getSystemDirectory()
        for (fileName in missingFileNames) {
            downloadSingle(fileName, systemDir)
        }
    }

    private suspend fun downloadSingle(fileName: String, systemDir: File) {
        val destFile = File(systemDir, fileName)
        val url = "$BASE_URL/$fileName"

        // Create parent directory if needed (e.g. dc/ for Dreamcast BIOS)
        destFile.parentFile?.mkdirs()

        Timber.i("BiosDownloader: downloading $fileName")
        // Ensure any partial/corrupt file is removed if the download fails for any reason,
        // so that getMissingBiosFiles() correctly identifies it as missing on the next launch.
        var downloadSucceeded = false
        try {
            downloadWithResume(url, destFile)
            downloadSucceeded = true
        } finally {
            if (!downloadSucceeded) {
                destFile.delete()
            }
        }

        val expectedMd5 = BiosManager.biosEntryFor(fileName)?.md5
        if (expectedMd5 != null) {
            val actualMd5 = md5Hex(destFile)
            if (!actualMd5.equals(expectedMd5, ignoreCase = true)) {
                destFile.delete()
                throw IOException("MD5 mismatch for $fileName: expected=$expectedMd5 actual=$actualMd5")
            }
            Timber.i("BiosDownloader: $fileName MD5 OK")
        } else {
            Timber.w("BiosDownloader: no MD5 entry for $fileName, skipping verification")
        }
    }

    private suspend fun downloadWithResume(url: String, destFile: File) {
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
                        if (response.code == 416) return@withContext // already complete
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
                if (attempt >= MAX_RETRIES) throw IOException("BIOS download failed after $MAX_RETRIES attempts", e)
                Timber.w("BiosDownloader attempt $attempt failed: ${e.message}, retrying…")
                delay(minOf(2000L * attempt, 10_000L))
            }
        }
    }

    private fun md5Hex(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            var n: Int
            while (input.read(buffer).also { n = it } != -1) {
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02X".format(it) }
    }
}
