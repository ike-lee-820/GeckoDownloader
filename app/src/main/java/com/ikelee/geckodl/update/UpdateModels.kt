package com.ikelee.geckodl.update

import java.io.File
import java.util.Locale

data class ReleaseInfo(
    val tag: String,
    val name: String,
    val notes: String,
    val publishedAt: String,
    val htmlUrl: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long
)

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState

    /** manual = true when the check came from Settings (show feedback even if there is no update). */
    data class Available(val release: ReleaseInfo, val manual: Boolean) : UpdateUiState

    data class Downloading(
        val release: ReleaseInfo,
        val downloaded: Long,
        val total: Long,
        val threads: Int
    ) : UpdateUiState

    data class Ready(val release: ReleaseInfo, val apk: File) : UpdateUiState
    data class Failed(val message: String, val release: ReleaseInfo?) : UpdateUiState
}

/** Compares YY.MM.N style versions (also tolerates a leading "v" and extra suffixes). */
fun isNewerVersion(remote: String, local: String): Boolean {
    fun parts(s: String): List<Int> = s.trim()
        .removePrefix("v").removePrefix("V")
        .split(Regex("[._-]"))
        .mapNotNull { it.toIntOrNull() }

    val r = parts(remote)
    val l = parts(local)
    if (r.isEmpty()) return false
    val n = maxOf(r.size, l.size)
    for (i in 0 until n) {
        val a = r.getOrElse(i) { 0 }
        val b = l.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}
