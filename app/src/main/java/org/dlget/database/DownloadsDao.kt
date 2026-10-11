package org.dlget.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadsDao {
    @Insert
    suspend fun insert(item: Download): Long

    @Update
    suspend fun update(item: Download)

    @Delete
    suspend fun delete(item: Download)

    @Query("SELECT * from downloads_table WHERE id = :id")
    fun getById(id: Long): Download?

    @Query("DELETE FROM downloads_table WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: Set<Long>)

    @Query("DELETE FROM downloads_table")
    suspend fun clearAll()

    @Query("SELECT * from downloads_table ORDER BY timestamp DESC")
    fun getAllDownloads(): Flow<List<Download>>

}