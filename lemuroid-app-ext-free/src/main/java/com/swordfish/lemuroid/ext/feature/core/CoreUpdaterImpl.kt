/*
 * CoreManager.kt
 *
 * Copyright (C) 2017 Retrograde Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.swordfish.lemuroid.ext.feature.core

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import com.swordfish.lemuroid.common.files.safeDelete
import com.swordfish.lemuroid.lib.core.CoreUpdater
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class CoreUpdaterImpl(
    private val directoriesManager: DirectoriesManager,
    retrofit: Retrofit,
) : CoreUpdater {
    // This is the last tagged versions of cores.
    companion object {
        private const val CORES_VERSION = com.swordfish.lemuroid.lib.core.CoreDownloader.CORES_VERSION
        /** Tamanho mínimo em bytes para considerar um arquivo de core como válido (100 KB). */
        private const val MIN_VALID_CORE_SIZE_BYTES = 100 * 1024L
    }

    private val baseUri = Uri.parse("https://raw.githubusercontent.com/luistiagos/libretrocores/")

    private val api = retrofit.create(CoreUpdater.CoreManagerApi::class.java)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override suspend fun downloadCores(
        context: Context,
        coreIDs: List<CoreID>,
    ) {
        val sharedPreferences = SharedPreferencesHelper.getSharedPreferences(context.applicationContext)
        coreIDs.asFlow()
            .flatMapMerge(concurrency = 4) { coreID ->
                flow {
                    try {
                        retrieveAssets(coreID, sharedPreferences)
                        retrieveFile(context, coreID)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Timber.e(e, "Failed to download core $coreID: ${e.message}")
                    }
                    emit(coreID)
                }
            }
            .collect()
    }

    private suspend fun retrieveFile(
        context: Context,
        coreID: CoreID,
    ) {
        findBundledLibrary(context, coreID) ?: downloadCoreFromGithub(context, coreID)
    }

    private suspend fun retrieveAssets(
        coreID: CoreID,
        sharedPreferences: SharedPreferences,
    ) {
        CoreID.getAssetManager(coreID)
            .retrieveAssetsIfNeeded(api, directoriesManager, sharedPreferences)
    }

    private suspend fun downloadCoreFromGithub(
        context: Context,
        coreID: CoreID,
    ): File {
        Timber.i("Downloading core $coreID from github")

        // Use AbiUtils para garantir o nome canônico da ABI (ex: "arm64-v8a" e não "arm64").
        val processAbi = com.swordfish.lemuroid.lib.util.AbiUtils.getProcessAbi(context)

        val mainCoresDirectory = directoriesManager.getCoresDirectory()
        val coresDirectory =
            File(mainCoresDirectory, CORES_VERSION).apply {
                mkdirs()
            }

        val libFileName = coreID.libretroFileName
        val destFile = File(coresDirectory, libFileName)

        val isValid = destFile.exists() &&
                destFile.length() > MIN_VALID_CORE_SIZE_BYTES &&
                com.swordfish.lemuroid.lib.util.AbiUtils.isElfCompatible(destFile, processAbi)

        if (isValid) {
            destFile.setExecutable(true, true)
            return destFile
        }

        // Arquivo não existe ou está corrompido/incompatível: remove e re-baixa.
        if (destFile.exists()) {
            Timber.w("Core file exists but is invalid or incompatible, deleting and re-downloading: $destFile")
            destFile.safeDelete()
        }

        runCatching {
            deleteOutdatedCores(mainCoresDirectory, CORES_VERSION)
        }

        val uri =
            baseUri.buildUpon()
                .appendEncodedPath("$CORES_VERSION/lemuroid_core_${coreID.coreName}/src/main/jniLibs/")
                .appendPath(processAbi)
                .appendPath(libFileName)
                .build()

        try {
            downloadFileWithResume(uri.toString(), destFile)
            destFile.setExecutable(true, true)
            return destFile
        } catch (e: Throwable) {
            destFile.safeDelete()
            throw e
        }
    }

    private suspend fun downloadFileWithResume(url: String, destFile: File) {
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
                        if (response.code == 416) return@withContext // file already complete
                        if (response.code == 429) throw IOException("429 rate limited")
                        if (response.code in 400..499) throw IOException("Permanent HTTP ${response.code}")
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val isResume = response.code == 206
                        val body = response.body ?: throw IOException("Empty response body")
                        Timber.d("Downloading core: $destFile (resume=$isResume, offset=$existingBytes)")
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
                Timber.i("Core download complete: $destFile (${destFile.length()} bytes)")
                return // success
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (e.message?.startsWith("Permanent") == true) throw e
                if (attempt >= maxRetries) throw IOException("Core download failed after $maxRetries attempts: ${e.message}", e)
                Timber.w("Core download attempt $attempt failed: ${e.message}, retrying...")
                delay(minOf(2000L * attempt, 10000L))
            }
        }
    }

    private suspend fun findBundledLibrary(
        context: Context,
        coreID: CoreID,
    ): File? =
        withContext(Dispatchers.IO) {
            File(context.applicationInfo.nativeLibraryDir)
                .walkBottomUp()
                .firstOrNull { it.name == coreID.libretroFileName }
        }

    private fun deleteOutdatedCores(
        mainCoresDirectory: File,
        applicationVersion: String,
    ) {
        mainCoresDirectory.listFiles()
            ?.filter { it.name != applicationVersion }
            ?.forEach { it.deleteRecursively() }
    }
}
