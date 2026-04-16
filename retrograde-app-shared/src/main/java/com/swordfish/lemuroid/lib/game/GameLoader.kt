/*
 * GameLoader.kt
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

package com.swordfish.lemuroid.lib.game

import android.content.Context
import com.swordfish.lemuroid.lib.bios.BiosManager
import com.swordfish.lemuroid.lib.core.CoreVariable
import com.swordfish.lemuroid.lib.core.CoreVariablesManager
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.LemuroidLibrary
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.migration.DesmumeMigrationHandler
import com.swordfish.lemuroid.lib.saves.SaveState
import com.swordfish.lemuroid.lib.saves.SavesCoherencyEngine
import com.swordfish.lemuroid.lib.saves.SavesManager
import com.swordfish.lemuroid.lib.saves.StatesManager
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import com.swordfish.lemuroid.lib.storage.RomFiles
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.io.File

class GameLoader(
    private val lemuroidLibrary: LemuroidLibrary,
    private val statesManager: StatesManager,
    private val savesManager: SavesManager,
    private val coreVariablesManager: CoreVariablesManager,
    private val retrogradeDatabase: RetrogradeDatabase,
    private val savesCoherencyEngine: SavesCoherencyEngine,
    private val directoriesManager: DirectoriesManager,
    private val biosManager: BiosManager,
    private val desmumeMigrationHandler: DesmumeMigrationHandler,
) {
    sealed class LoadingState {
        object LoadingCore : LoadingState()

        object LoadingGame : LoadingState()

        class Ready(val gameData: GameData) : LoadingState()
    }

    fun load(
        appContext: Context,
        game: Game,
        loadSave: Boolean,
        systemCoreConfig: SystemCoreConfig,
        directLoad: Boolean,
    ): Flow<LoadingState> =
        flow {
            try {
                emit(LoadingState.LoadingCore)

                val system = GameSystem.findByIdOrNull(game.systemId)
                    ?: throw GameLoaderException(GameLoaderError.Generic)

                if (!isArchitectureSupported(appContext, systemCoreConfig)) {
                    throw GameLoaderException(GameLoaderError.UnsupportedArchitecture)
                }

                val coreLibrary =
                    runCatching {
                        findLibrary(appContext, systemCoreConfig.coreID)!!.absolutePath
                    }.getOrElse { throw GameLoaderException(GameLoaderError.LoadCore) }

                emit(LoadingState.LoadingGame)

                // Run independent I/O tasks in parallel to reduce total wall-clock time
                // on weak devices. Only quickSaveData depends on saveRAM (timestamp), so
                // it runs after the SRAM deferred completes.
                val (gameFiles, saveRAM, coreVariables, systemDirectory, savesDirectory, missingBiosFiles) =
                    coroutineScope {
                        val deferredBios = async { biosManager.getMissingBiosFiles(systemCoreConfig, game) }
                        val deferredGameFiles = async {
                            val useVFS = systemCoreConfig.supportsLibretroVFS && directLoad
                            val dataFiles = retrogradeDatabase.dataFileDao().selectDataFilesForGame(game.id)
                            lemuroidLibrary.getGameFiles(game, dataFiles, useVFS)
                        }
                        val deferredSaveRAM = async {
                            val data = savesManager.getSaveRAM(game, systemCoreConfig)
                            desmumeMigrationHandler.resolveSaveData(game, systemCoreConfig.coreID, data)
                        }
                        val deferredCoreVars = async {
                            coreVariablesManager.getOptionsForCore(system.id, systemCoreConfig)
                        }
                        val deferredSystemDir = async { directoriesManager.getSystemDirectory() }
                        val deferredSavesDir = async { directoriesManager.getSavesDirectory() }

                        ParallelResult(
                            deferredGameFiles.await(),
                            deferredSaveRAM.await(),
                            deferredCoreVars.await(),
                            deferredSystemDir.await(),
                            deferredSavesDir.await(),
                            deferredBios.await(),
                        )
                    }

                if (missingBiosFiles.isNotEmpty()) {
                    throw GameLoaderException(GameLoaderError.MissingBiosFiles(missingBiosFiles))
                }

                val saveRAMData = saveRAM.data

                val quickSaveData =
                    runCatching {
                        val shouldDiscardSave =
                            !savesCoherencyEngine.shouldDiscardAutoSaveState(
                                game,
                                systemCoreConfig.coreID,
                                saveRAM.timestampOverride,
                            )

                        if (systemCoreConfig.statesSupported && loadSave && shouldDiscardSave) {
                            statesManager.getAutoSave(game, systemCoreConfig.coreID)
                        } else {
                            null
                        }
                    }.getOrElse { throw GameLoaderException(GameLoaderError.Saves) }

                emit(
                    LoadingState.Ready(
                        GameData(
                            game,
                            coreLibrary,
                            gameFiles,
                            quickSaveData,
                            saveRAMData,
                            coreVariables.toTypedArray(),
                            systemDirectory,
                            savesDirectory,
                        ),
                    ),
                )
            } catch (e: GameLoaderException) {
                Timber.e(e, "Error while preparing game")
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error while preparing game")
                throw GameLoaderException(GameLoaderError.Generic)
            }
        }

    private fun isArchitectureSupported(
        context: Context,
        systemCoreConfig: SystemCoreConfig,
    ): Boolean {
        val supportedOnlyArchitectures = systemCoreConfig.supportedOnlyArchitectures ?: return true
        val processAbi = getProcessAbi(context)
        return processAbi in supportedOnlyArchitectures
    }

    /** In-memory cache of core library paths to avoid repeated filesystem walks. */
    private val corePathCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Cached ABI string so we don't re-read it on every findLibrary call. */
    @Volatile private var cachedProcessAbi: String? = null

    private fun getProcessAbi(context: Context): String {
        cachedProcessAbi?.let { return it }
        val abi = com.swordfish.lemuroid.lib.util.AbiUtils.getProcessAbi(context)
        cachedProcessAbi = abi
        return abi
    }

    private fun findLibrary(
        context: Context,
        coreID: CoreID,
    ): File? {
        val processAbi = getProcessAbi(context)
        val libFileName = coreID.libretroFileName

        // Fast path 0: check in-memory cache — skip ELF validation (already validated once)
        corePathCache[libFileName]?.let { cachedPath ->
            val cached = File(cachedPath)
            if (cached.exists() && cached.length() > MIN_VALID_CORE_SIZE_BYTES) return cached
            corePathCache.remove(libFileName)
        }

        // Fast path 1: core bundled inside the APK (nativeLibraryDir)
        val bundled = File(context.applicationInfo.nativeLibraryDir, libFileName)
        if (bundled.exists() && bundled.length() > MIN_VALID_CORE_SIZE_BYTES &&
            com.swordfish.lemuroid.lib.util.AbiUtils.isElfCompatible(bundled, processAbi)
        ) {
            corePathCache[libFileName] = bundled.absolutePath
            return bundled
        }

        // Fast path 2: downloaded core at its deterministic versioned path
        val downloaded = File(
            context.filesDir,
            "cores/${com.swordfish.lemuroid.lib.core.CoreDownloader.CORES_VERSION}/$libFileName",
        )
        if (downloaded.exists() && downloaded.length() > MIN_VALID_CORE_SIZE_BYTES &&
            com.swordfish.lemuroid.lib.util.AbiUtils.isElfCompatible(downloaded, processAbi)
        ) {
            corePathCache[libFileName] = downloaded.absolutePath
            return downloaded
        }

        // Slow path fallback: walk filesDir for cores in non-standard locations
        val found = sequenceOf(
            File(context.applicationInfo.nativeLibraryDir),
            context.filesDir,
        )
            .flatMap { it.walkBottomUp() }
            .firstOrNull { file ->
                file.name == libFileName &&
                    file.length() > MIN_VALID_CORE_SIZE_BYTES &&
                    com.swordfish.lemuroid.lib.util.AbiUtils.isElfCompatible(file, processAbi)
            }

        if (found != null) {
            corePathCache[libFileName] = found.absolutePath
        }
        return found
    }

    /** Container for results from parallelized I/O tasks in [load]. */
    private data class ParallelResult(
        val gameFiles: RomFiles,
        val saveRAM: DesmumeMigrationHandler.SaveDataResult,
        val coreVariables: List<CoreVariable>,
        val systemDirectory: File,
        val savesDirectory: File,
        val missingBiosFiles: List<String>,
    )

    companion object {
        /** Minimum file size (100 KB) to consider a core .so as non-corrupt. */
        private const val MIN_VALID_CORE_SIZE_BYTES = 100 * 1024L
    }

    @Suppress("ArrayInDataClass")
    data class GameData(
        val game: Game,
        val coreLibrary: String,
        val gameFiles: RomFiles,
        val quickSaveData: SaveState?,
        val saveRAMData: ByteArray?,
        val coreVariables: Array<CoreVariable>,
        val systemDirectory: File,
        val savesDirectory: File,
    )
}
