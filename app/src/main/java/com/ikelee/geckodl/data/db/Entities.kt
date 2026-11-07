package com.ikelee.geckodl.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DownloadKind { HTTP, HLS }

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELED }

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val referer: String? = null,
    val cookie: String? = null,
    val userAgent: String? = null,
    val kind: DownloadKind,
    val fileName: String,
    /** A plain file path for app storage, or a content:// tree document URI for SAF. */
    val outputUri: String,
    val totalBytes: Long = -1L,
    val downloadedBytes: Long = 0L,
    val threads: Int = 4,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val priority: Int = 0,
    val error: String? = null,
    val segmentsTotal: Int = 0,
    val segmentsDone: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** Byte range of one HTTP chunk, and how far it has got. Persisted for resume. */
@Entity(tableName = "chunks", primaryKeys = ["taskId", "chunkIndex"])
data class ChunkEntity(
    val taskId: Long,
    val chunkIndex: Int,
    val start: Long,
    val end: Long,
    val done: Long,
    val finished: Boolean
)
