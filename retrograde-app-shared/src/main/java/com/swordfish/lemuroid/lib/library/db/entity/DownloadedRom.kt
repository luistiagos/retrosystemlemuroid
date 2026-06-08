package com.swordfish.lemuroid.lib.library.db.entity

import androidx.room.Entity

/**
 * Tracks ROMs that have been individually downloaded on demand.
 * Primary key is the catalog path inside the system, because filenames can repeat
 * across consoles (for example PSX and Dreamcast can both have the same CHD name).
 *
 * A row being present here means the physical file on disk has content (> 0 bytes).
 * When the user deletes a ROM, the row is removed and the file is replaced with a
 * 0-byte placeholder so the game stays visible in the library for re-download.
 */
@Entity(
    tableName = "downloaded_roms",
    primaryKeys = ["systemId", "fileName"],
)
data class DownloadedRom(
    val systemId: String,
    val fileName: String,
    val fileSize: Long,
    val downloadedAt: Long = System.currentTimeMillis(),
)
