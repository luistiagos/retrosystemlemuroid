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
import com.swordfish.lemuroid.common.kotlin.writeToFile
import com.swordfish.lemuroid.lib.core.CoreUpdater
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import timber.log.Timber
import java.io.File

class CoreUpdaterImpl(
    private val directoriesManager: DirectoriesManager,
    retrofit: Retrofit,
) : CoreUpdater {
    // This is the last tagged versions of cores.
    companion object {
        private const val CORES_VERSION = "1.17.0"
        /** Tamanho mínimo em bytes para considerar um arquivo de core como válido (100 KB). */
        private const val MIN_VALID_CORE_SIZE_BYTES = 100 * 1024L
    }

    private val baseUri = Uri.parse("https://github.com/Swordfish90/LemuroidCores/")

    private val api = retrofit.create(CoreUpdater.CoreManagerApi::class.java)

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
                .appendEncodedPath("raw/$CORES_VERSION/lemuroid_core_${coreID.coreName}/src/main/jniLibs/")
                .appendPath(processAbi)
                .appendPath(libFileName)
                .build()

        try {
            downloadFile(uri, destFile)
            destFile.setExecutable(true, true)
            return destFile
        } catch (e: Throwable) {
            destFile.safeDelete()
            throw e
        }
    }

    private suspend fun downloadFile(
        uri: Uri,
        destFile: File,
    ) {
        val response = api.downloadFile(uri.toString())

        if (!response.isSuccessful) {
            Timber.e("Download core response was unsuccessful")
            throw Exception(response.errorBody()?.string() ?: "Download error")
        }

        response.body()?.writeToFile(destFile)
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
