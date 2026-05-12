package com.swordfish.lemuroid.lib.library.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persists the save (download) queue between app sessions.
 * State values: QUEUED, SAVING, PAUSED (terminal states SAVED/ERROR are removed after display).
 */
@Entity(
    tableName = "save_queue",
    indices = [Index("fileName", unique = true)],
)
data class SaveQueueItem(
    @PrimaryKey val fileName: String,
    val gameId: Int,
    val gameTitle: String,
    val gameCoverUrl: String?,
    val gameFileUri: String,
    val systemId: String,
    val state: String,
    val addedAt: Long = System.currentTimeMillis(),
    val position: Int = 0,
)
