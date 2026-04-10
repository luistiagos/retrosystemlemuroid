package com.swordfish.lemuroid.lib.library.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks ROMs that have been individually downloaded on demand.
 * Primary key is the ROM fileName (e.g. "Super Mario World (USA).sfc") which is
 * unique within the whole catalog.
 *
 * A row being present here means the physical file on disk has content (> 0 bytes).
 * When the user deletes a ROM, the row is removed and the file is replaced with a
 * 0-byte placeholder so the game stays visible in the library for re-download.
 */
@Entity(
    tableName = "downloaded_roms",
    indices = [Index("fileName", unique = true)],
)
data class DownloadedRom(
    @PrimaryKey
    val fileName: String,
    val fileSize: Long,
    val downloadedAt: Long = System.currentTimeMillis(),
)
