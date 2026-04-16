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
     * Uses [SmartStoragePicker] to automatically select the volume with the most free
     * space when the user has not configured a custom directory, so that SD cards and
     * USB drives attached to Smart TVs are preferred over limited built-in flash.
     */
    fun getInternalRomsDirectory(): File = cachedRoms
}
