package com.ikelee.geckodl.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Downloads the update APK with up to 32 parallel ranged requests.
 *
 * The v6.github.com proxy is tried first. Every candidate URL is probed, ones that honour
 * Range are preferred so the 32-thread path is actually used; if the proxy ignores Range or
 * fails, the original github.com URL is tried. A server that ignores Range falls back to a
 * single stream. The finished file is checked for size and ZIP/AndroidManifest structure so
 * an HTML error page from a proxy is never offered as an installable APK.
 */
@Singleton
class ApkDownloader @Inject constructor(private val http: OkHttpClient) {

    companion object {
        const val THREADS = 32
        private const val BUFFER = 64 * 1024
        private const val MIN_SEGMENT = 256L * 1024L
    }

    data class ProbeInfo(val totalBytes: Long, val rangeSupported: Boolean)

    private fun request(url: String, range: String? = null): Request {
        val b = Request.Builder().url(url).header("User-Agent", UpdateConfig.USER_AGENT)
        if (range != null) b.header("Range", range)
        return b.build()
    }

    private fun probe(url: String): ProbeInfo {
        http.newCall(request(url, "bytes=0-0")).execute().use { resp ->
            return when (resp.code) {
                206 -> ProbeInfo(
                    totalBytes = resp.header("Content-Range")
                        ?.substringAfterLast('/')?.toLongOrNull() ?: -1L,
                    rangeSupported = true
                )
                200 -> ProbeInfo(resp.body?.contentLength() ?: -1L, false)
                else -> throw IOException("HTTP ${resp.code} while probing update")
            }
        }
    }

    /**
     * Tries the proxy then the direct URL. Calls [onProgress] with
     * (bytes done, total bytes, active threads) from IO threads.
     */
    suspend fun downloadWithFallback(
        urls: List<String>,
        dest: File,
        threads: Int = THREADS,
        onProgress: (Long, Long, Int) -> Unit
    ): File {
        val probed = mutableListOf<Pair<String, ProbeInfo>>()
        for (url in urls.distinct()) {
            try {
                probed += url to probe(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep probing the other endpoint; errors are collected below.
            }
        }
        if (probed.isEmpty()) throw IOException("Both the v6.github.com proxy and github.com are unreachable")

        // Stable sort: Range-capable endpoints first, proxy keeps priority among equals.
        val ordered = probed.sortedByDescending { it.second.rangeSupported && it.second.totalBytes > 0L }

        var lastError: Exception? = null
        for ((url, info) in ordered) {
            try {
                dest.parentFile?.mkdirs()
                if (dest.exists()) dest.delete()
                if (info.rangeSupported && info.totalBytes > 0L) {
                    ranged(url, dest, info.totalBytes, threads, onProgress)
                } else {
                    single(url, dest, info.totalBytes, onProgress)
                }
                verify(dest, info.totalBytes)
                return dest
            } catch (e: CancellationException) {
                dest.delete()
                throw e
            } catch (e: Exception) {
                lastError = e
                dest.delete()
            }
        }
        throw lastError ?: IOException("Update download failed")
    }

    private suspend fun ranged(
        url: String,
        dest: File,
        total: Long,
        threads: Int,
        onProgress: (Long, Long, Int) -> Unit
    ) = coroutineScope {
        val segments = plan(total, threads)
        RandomAccessFile(dest, "rw").use { raf ->
            raf.setLength(total)
            val channel = raf.channel
            val done = AtomicLong(0L)

            segments.map { range ->
                async(Dispatchers.IO) {
                    var attempt = 0
                    while (true) {
                        try {
                            copyRange(url, channel, range.first, range.last, done, total, segments.size, onProgress)
                            break
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: IOException) {
                            if (++attempt > 2) throw e
                            delay(500L * attempt)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun copyRange(
        url: String,
        channel: java.nio.channels.FileChannel,
        start: Long,
        end: Long,
        done: AtomicLong,
        total: Long,
        threads: Int,
        onProgress: (Long, Long, Int) -> Unit
    ) {
        val call = http.newCall(request(url, "bytes=$start-$end"))
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }

        call.execute().use { resp ->
            if (resp.code != 206) {
                throw IOException("Server ignored Range (HTTP ${resp.code}); falling back to another endpoint")
            }
            val body = resp.body ?: throw IOException("Empty response body")
            body.byteStream().use { input ->
                val buf = ByteArray(BUFFER)
                var pos = start
                while (pos <= end) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n <= 0) break
                    if (pos + n - 1 > end) throw IOException("Range response exceeded requested bytes")

                    val bb = ByteBuffer.wrap(buf, 0, n)
                    var written = 0
                    while (bb.hasRemaining()) {
                        val w = channel.write(bb, pos + written)
                        if (w <= 0) throw IOException("Failed writing APK chunk")
                        written += w
                    }
                    pos += written
                    onProgress(done.addAndGet(written.toLong()), total, threads)
                }
                if (pos <= end) throw IOException("Incomplete range: ${end - pos + 1} bytes missing")
            }
        }
    }

    private suspend fun single(
        url: String,
        dest: File,
        total: Long,
        onProgress: (Long, Long, Int) -> Unit
    ) {
        val call = http.newCall(request(url))
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} while downloading update")
            val body = resp.body ?: throw IOException("Empty response body")
            var done = 0L
            body.byteStream().use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, if (total > 0) total else done, 1)
                    }
                }
            }
        }
    }

    /** At most [threads] ranges, each roughly >= 256 KB so a 50 MB APK really uses 32 threads. */
    private fun plan(total: Long, threads: Int): List<LongRange> {
        val count = min(threads.toLong(), maxOf(1L, total / MIN_SEGMENT)).toInt().coerceAtLeast(1)
        val size = total / count
        return (0 until count).map { i ->
            val start = i * size
            val end = if (i == count - 1) total - 1 else start + size - 1
            start..end
        }
    }

    private fun verify(file: File, expected: Long) {
        if (!file.exists() || file.length() < 1024L) throw IOException("Downloaded file is too small to be an APK")
        if (expected > 0L && file.length() != expected) {
            throw IOException("Size mismatch: expected $expected, got ${file.length()}")
        }
        ZipFile(file).use { zip ->
            if (zip.getEntry("AndroidManifest.xml") == null) {
                throw IOException("Download is not a valid APK (proxy returned a web page?)")
            }
        }
    }
}
