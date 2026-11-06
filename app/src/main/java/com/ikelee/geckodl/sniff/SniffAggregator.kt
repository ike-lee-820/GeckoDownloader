package com.ikelee.geckodl.sniff

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Receives messages from the sniffer extension, de-duplicates them per tab, and groups
 * HLS segments under their playlist. Tab attribution uses the tab that was active when
 * the message arrived, because GeckoView does not expose the session id to the extension.
 */
@Singleton
class SniffAggregator @Inject constructor() {

    private val _byTab = MutableStateFlow<Map<String, List<SniffedResource>>>(emptyMap())
    val byTab: StateFlow<Map<String, List<SniffedResource>>> = _byTab.asStateFlow()

    /** Keeps a per-tab list of playlists and media. Segments only increment a playlist's count. */
    fun ingest(tabKey: String, json: JSONObject) {
        val url = json.optString("url").takeIf { it.isNotBlank() } ?: return
        val contentType = json.optString("contentType")
        val pageUrl = json.optString("pageUrl")
        val method = json.optString("method", "GET")
        val headers = json.optJSONObject("headers")?.let { h ->
            h.keys().asSequence().associateWith { k -> h.optString(k) }
        } ?: emptyMap()

        val kind = classify(url, contentType) ?: return
        val current = _byTab.value[tabKey].orEmpty().toMutableList()

        if (kind == SniffKind.HLS || isSegment(url, contentType)) {
            // Segments attach to the playlist from the same page, if one has been seen.
            val playlistIndex = current.indexOfFirst { it.kind == SniffKind.HLS && it.pageUrl == pageUrl }
            if (isSegment(url, contentType)) {
                if (playlistIndex >= 0) {
                    val p = current[playlistIndex]
                    current[playlistIndex] = p.copy(segmentCount = p.segmentCount + 1)
                    _byTab.value = _byTab.value + (tabKey to current)
                }
                return
            }
        }

        if (current.any { it.url == url }) return
        current += SniffedResource(url, kind, method, contentType, pageUrl, headers)
        _byTab.value = _byTab.value + (tabKey to current)
    }

    fun resourcesFor(tabKey: String): List<SniffedResource> = _byTab.value[tabKey].orEmpty()

    fun clear(tabKey: String) {
        _byTab.value = _byTab.value - tabKey
    }

    private fun classify(url: String, contentType: String): SniffKind? {
        val ct = contentType.lowercase()
        val path = url.substringBefore('?').lowercase()
        return when {
            ct.contains("mpegurl") || path.endsWith(".m3u8") -> SniffKind.HLS
            ct.contains("dash+xml") || path.endsWith(".mpd") -> SniffKind.DASH
            path.endsWith(".mp4") || ct == "video/mp4" -> SniffKind.MP4
            path.endsWith(".mp3") || path.endsWith(".m4a") || ct.startsWith("audio/") -> SniffKind.AUDIO
            ct.startsWith("video/") || path.endsWith(".webm") || path.endsWith(".flv") -> SniffKind.OTHER_MEDIA
            else -> null
        }
    }

    private fun isSegment(url: String, contentType: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".ts") || path.endsWith(".m4s") || contentType.lowercase() == "video/mp2t"
    }
}
