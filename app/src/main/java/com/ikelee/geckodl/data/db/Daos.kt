package com.ikelee.geckodl.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY priority DESC, createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status IN (:statuses) ORDER BY priority DESC, createdAt ASC")
    suspend fun withStatus(statuses: List<DownloadStatus>): List<DownloadEntity>

    @Insert
    suspend fun insert(entity: DownloadEntity): Long

    @Update
    suspend fun update(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ChunkDao {
    @Query("SELECT * FROM chunks WHERE taskId = :taskId ORDER BY chunkIndex")
    suspend fun forTask(taskId: Long): List<ChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chunks: List<ChunkEntity>)

    @Query("DELETE FROM chunks WHERE taskId = :taskId")
    suspend fun clear(taskId: Long)
}

@Dao
interface BrowserDao {
    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT 200")
    fun observeHistory(): Flow<List<HistoryEntity>>

    @Insert
    suspend fun addHistory(entity: HistoryEntity)

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeBookmarks(): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun addBookmark(entity: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)
}
