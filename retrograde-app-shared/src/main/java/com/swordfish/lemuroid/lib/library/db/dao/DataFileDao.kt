package com.swordfish.lemuroid.lib.library.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.swordfish.lemuroid.lib.library.db.entity.DataFile

@Dao
interface DataFileDao {
    @Query("SELECT * FROM datafiles where gameId = :gameId")
    fun selectDataFilesForGame(gameId: Int): List<DataFile>

    @Query("SELECT * FROM datafiles WHERE lastIndexedAt < :lastIndexedAt")
    suspend fun selectByLastIndexedAtLessThan(lastIndexedAt: Long): List<DataFile>

    @Query("DELETE FROM datafiles WHERE lastIndexedAt < :lastIndexedAt")
    suspend fun deleteByLastIndexedAtLessThan(lastIndexedAt: Long)

    @Insert
    fun insert(dataFile: DataFile)

    @Insert
    suspend fun insert(dataFiles: List<DataFile>)

    @Delete
    suspend fun delete(dataFiles: List<DataFile>)
}
