package com.swordfish.lemuroid.lib.storage

import android.content.Context
import java.io.File

class DirectoriesManager(private val appContext: Context) {

    private val cachedInternalStates by lazy {
        File(appContext.filesDir, "states").apply { mkdirs() }
    }

    private val cachedCores by lazy {
        File(appContext.filesDir, "cores").apply { mkdirs() }
    }

    private val cachedSystem by lazy {
        File(appContext.filesDir, "system").apply { mkdirs() }
    }

    private val externalBase by lazy { appContext.getExternalFilesDir(null) }

    private val cachedStates by lazy {
        File(externalBase, "states").apply { mkdirs() }
    }

    private val cachedStatesPreview by lazy {
        File(externalBase, "state-previews").apply { mkdirs() }
    }

    private val cachedSaves by lazy {
        File(externalBase, "saves").apply { mkdirs() }
    }

    private val cachedRoms by lazy {
        SmartStoragePicker.getBestRomsDirectory(appContext)
    }

    @Deprecated("Use the external states directory")
    fun getInternalStatesDirectory(): File = cachedInternalStates

    fun getCoresDirectory(): File = cachedCores

    fun getSystemDirectory(): File = cachedSystem

    fun getStatesDirectory(): File = cachedStates

    fun getStatesPreviewDirectory(): File = cachedStatesPreview

    fun getSavesDirectory(): File = cachedSaves

    /**
     * Returns the directory where ROMs should be stored/scanned.
     * The volume is picked by [SmartStoragePicker] (most free space, so SD cards and USB
     * drives on Smart TVs beat limited built-in flash) once, and then kept across launches.
     */
    fun getInternalRomsDirectory(): File = cachedRoms

    /**
     * Fragment present in the path/URI of anything under the managed ROMs dir of ANY volume
     * — see [RomsDirChoice.managedRomsMarker]. Rows can live on a volume other than
     * [getInternalRomsDirectory] (downloads kept where they were when the volume changed).
     */
    fun getManagedRomsMarker(): String = RomsDirChoice.managedRomsMarker(appContext.packageName)
}
