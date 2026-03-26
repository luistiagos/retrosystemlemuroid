package com.swordfish.lemuroid.lib.storage

import android.content.Context
import java.io.File

class DirectoriesManager(private val appContext: Context) {
    @Deprecated("Use the external states directory")
    fun getInternalStatesDirectory(): File =
        File(appContext.filesDir, "states").apply {
            mkdirs()
        }

    fun getCoresDirectory(): File =
        File(appContext.filesDir, "cores").apply {
            mkdirs()
        }

    fun getSystemDirectory(): File =
        File(appContext.filesDir, "system").apply {
            mkdirs()
        }

    fun getStatesDirectory(): File =
        File(appContext.getExternalFilesDir(null), "states").apply {
            mkdirs()
        }

    fun getStatesPreviewDirectory(): File =
        File(appContext.getExternalFilesDir(null), "state-previews").apply {
            mkdirs()
        }

    fun getSavesDirectory(): File =
        File(appContext.getExternalFilesDir(null), "saves").apply {
            mkdirs()
        }

    /**
     * Returns the directory where ROMs should be stored/scanned.
     * Uses [SmartStoragePicker] to automatically select the volume with the most free
     * space when the user has not configured a custom directory, so that SD cards and
     * USB drives attached to Smart TVs are preferred over limited built-in flash.
     */
    fun getInternalRomsDirectory(): File = SmartStoragePicker.getBestRomsDirectory(appContext)
}
