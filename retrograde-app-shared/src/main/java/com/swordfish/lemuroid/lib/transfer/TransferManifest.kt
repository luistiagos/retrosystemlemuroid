package com.swordfish.lemuroid.lib.transfer

import kotlinx.serialization.Serializable

@Serializable
data class TransferManifest(
    val version: Int = CURRENT_VERSION,
    val exportDate: Long,
    val appVersion: String,
    val appVersionCode: Int,
    val includesApk: Boolean = false,
    val apkFileName: String? = null,
    val games: List<TransferGameEntry>,
) {
    companion object {
        const val CURRENT_VERSION = 1
        const val MANIFEST_FILE_NAME = "manifest.json"
        const val EXPORT_DIR_NAME = "lemuroid-export"
        const val ROMS_DIR = "roms"
        const val SAVES_DIR = "saves"
        const val STATES_DIR = "states"
        const val STATE_PREVIEWS_DIR = "state-previews"
        const val APK_DIR = "app"
    }
}

@Serializable
data class TransferGameEntry(
    val fileName: String,
    val title: String,
    val systemId: String,
    val developer: String? = null,
    val coverFrontUrl: String? = null,
    val isFavorite: Boolean = false,
    val dataFiles: List<TransferDataFileEntry> = emptyList(),
)

@Serializable
data class TransferDataFileEntry(
    val fileName: String,
    val path: String? = null,
)
