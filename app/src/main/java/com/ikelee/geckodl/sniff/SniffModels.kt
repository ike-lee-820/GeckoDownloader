package com.ikelee.geckodl.sniff

enum class SniffKind { HLS, DASH, MP4, AUDIO, OTHER_MEDIA }

/**
 * One sniffed resource. HLS playlists carry [segmentCount]: the TS or m4s segments
 * that were seen for the playlist's page. Segments are never listed on their own.
 */
data class SniffedResource(
    val url: String,
    val kind: SniffKind,
    val method: String,
    val contentType: String,
    val pageUrl: String,
    val headers: Map<String, String>,
    val segmentCount: Int = 0,
    val seenAt: Long = System.currentTimeMillis()
)
