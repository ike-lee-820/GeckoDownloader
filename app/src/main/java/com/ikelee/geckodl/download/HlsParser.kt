package com.ikelee.geckodl.download

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class HlsVariant(val bandwidth: Long, val resolution: String?, val uri: String)

data class HlsKey(val method: String, val uri: String?, val iv: ByteArray?)

data class HlsSegment(val sequence: Long, val uri: String, val key: HlsKey?)

sealed class HlsPlaylist {
    data class Master(val variants: List<HlsVariant>) : HlsPlaylist()
    data class Media(
        val segments: List<HlsSegment>,
        val initUri: String?,
        val targetDuration: Int
    ) : HlsPlaylist()
}

/**
 * Parses RFC 8216 playlists that the sniffer and downloader need:
 * master variants, media segments, AES-128 keys, and EXT-X-MAP init segments.
 * Byte-range segments (EXT-X-BYTERANGE) and DASH MPD are not handled here.
 */
object HlsParser {

    fun parse(text: String, baseUrl: String): HlsPlaylist {
        val lines = text.lines().map { it.trim() }
        if (lines.none { it.startsWith("#EXTM3U") }) {
            throw IllegalArgumentException("Not an HLS playlist")
        }
        return if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            parseMaster(lines, baseUrl)
        } else {
            parseMedia(lines, baseUrl)
        }
    }

    private fun parseMaster(lines: List<String>, base: String): HlsPlaylist.Master {
        val variants = mutableListOf<HlsVariant>()
        var pending: Pair<Long, String?>? = null
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    val bw = attr(line, "BANDWIDTH")?.toLongOrNull() ?: 0L
                    pending = bw to attr(line, "RESOLUTION")
                }
                line.isNotEmpty() && !line.startsWith("#") && pending != null -> {
                    variants += HlsVariant(pending.first, pending.second, resolve(base, line))
                    pending = null
                }
            }
        }
        return HlsPlaylist.Master(variants.sortedByDescending { it.bandwidth })
    }

    private fun parseMedia(lines: List<String>, base: String): HlsPlaylist.Media {
        var sequence = 0L
        var targetDuration = 0
        var initUri: String? = null
        var key: HlsKey? = null
        val segments = mutableListOf<HlsSegment>()
        var index = 0L

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE") ->
                    sequence = line.substringAfter(':').toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-TARGETDURATION") ->
                    targetDuration = line.substringAfter(':').toIntOrNull() ?: 0
                line.startsWith("#EXT-X-MAP") ->
                    initUri = attr(line, "URI")?.let { resolve(base, it) }
                line.startsWith("#EXT-X-KEY") -> {
                    val method = attr(line, "METHOD") ?: "NONE"
                    key = if (method == "NONE") null else HlsKey(
                        method = method,
                        uri = attr(line, "URI")?.let { resolve(base, it) },
                        iv = attr(line, "IV")?.let { hexToBytes(it) }
                    )
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    segments += HlsSegment(sequence + index, resolve(base, line), key)
                    index++
                }
            }
        }
        return HlsPlaylist.Media(segments, initUri, targetDuration)
    }

    private fun attr(line: String, name: String): String? {
        val regex = Regex("$name=(\"[^\"]*\"|[^,]*)")
        return regex.find(line)?.groupValues?.get(1)?.trim('"')
    }

    private fun resolve(base: String, ref: String): String {
        val baseUrl = base.toHttpUrlOrNull() ?: return ref
        return baseUrl.resolve(ref)?.toString() ?: ref
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.removePrefix("0x").removePrefix("0X")
        val padded = clean.padStart(32, '0')
        return ByteArray(16) { i -> padded.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
