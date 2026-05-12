package com.swordfish.lemuroid.lib.library.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.swordfish.lemuroid.lib.library.db.entity.SaveQueueItem
import kotlinx.coroutines.flow.Flow

@Dao
interface SaveQueueDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: SaveQueueItem)

    @Query("SELECT * FROM save_queue ORDER BY position ASC, addedAt ASC")
    fun observeAll(): Flow<List<SaveQueueItem>>

    @Query("SELECT * FROM save_queue ORDER BY position ASC, addedAt ASC")
    suspend fun getAll(): List<SaveQueueItem>

    @Query("UPDATE save_queue SET state = :state WHERE fileName = :fileName")
    suspend fun updateState(fileName: String, state: String)

    @Query("DELETE FROM save_queue WHERE fileName = :fileName")
    suspend fun deleteByFileName(fileName: String)

    @Query("SELECT MAX(position) FROM save_queue")
    suspend fun maxPosition(): Int?

    @Query("SELECT EXISTS(SELECT 1 FROM save_queue WHERE fileName = :fileName)")
    fun observeIsQueued(fileName: String): Flow<Boolean>
}
