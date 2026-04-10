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

    @Query("DELETE FROM downloaded_roms WHERE fileName = :fileName")
    suspend fun deleteByFileName(fileName: String)

    @Query("SELECT EXISTS(SELECT 1 FROM downloaded_roms WHERE fileName = :fileName)")
    suspend fun isDownloaded(fileName: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM downloaded_roms WHERE fileName = :fileName)")
    fun observeIsDownloaded(fileName: String): Flow<Boolean>

    @Query("SELECT fileName FROM downloaded_roms")
    fun observeAllDownloadedFileNames(): Flow<List<String>>

    @Query("SELECT fileName FROM downloaded_roms")
    suspend fun getAllDownloadedFileNames(): List<String>
}
