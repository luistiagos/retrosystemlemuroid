package com.swordfish.lemuroid.lib.library.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.swordfish.lemuroid.lib.library.db.entity.DownloadedRom
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadedRomDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(downloadedRom: DownloadedRom)

    @Query("DELETE FROM downloaded_roms WHERE systemId = :systemId AND fileName = :fileName")
    suspend fun delete(systemId: String, fileName: String)

    /** v24 arcade reclassification: move a downloaded ROM's record from fbneo to its sub-system. */
    @Query("UPDATE OR IGNORE downloaded_roms SET systemId = :newSystem WHERE systemId = 'fbneo' AND fileName = :fileName")
    suspend fun reassignSystem(fileName: String, newSystem: String)

    @Query("SELECT EXISTS(SELECT 1 FROM downloaded_roms WHERE systemId = :systemId AND fileName = :fileName)")
    suspend fun isDownloaded(systemId: String, fileName: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM downloaded_roms WHERE systemId = :systemId AND fileName = :fileName)")
    fun observeIsDownloaded(systemId: String, fileName: String): Flow<Boolean>

    @Query("SELECT systemId || '/' || fileName FROM downloaded_roms")
    fun observeAllDownloadedKeys(): Flow<List<String>>

    @Query("SELECT systemId || '/' || fileName FROM downloaded_roms")
    suspend fun getAllDownloadedKeys(): List<String>
}
