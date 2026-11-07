package com.ikelee.geckodl.download

import com.ikelee.geckodl.data.db.ChunkEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.job
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/** Ranged chunk download. Chunk state is handed in and out so the engine can persist it. */
class HttpDownloader(
    private val http: OkHttpClient,
    private val limiter: BandwidthLimiter,
    private val taskLimiter: BandwidthLimiter
) {

    data class Probe(val totalBytes: Long, val rangeSupported: Boolean)

    /** Asks for byte 0 only, to learn the total size and whether ranges are honoured. */
    suspend fun probe(spec: RequestSpec): Probe = withContext(Dispatchers.IO) {
        val req = spec.apply(Request.Builder().url(spec.url)).header("Range", "bytes=0-0").build()
        http.newCall(req).execute().use { resp ->
            when (resp.code) {
                206 -> {
                    val total = resp.header("Content-Range")
                        ?.substringAfter('/')?.toLongOrNull() ?: -1L
                    Probe(total, true)
                }
                200 -> Probe(resp.body?.contentLength() ?: -1L, false)
                else -> throw IOException("HTTP ${resp.code} while probing")
            }
        }
    }

    /**
     * Downloads every unfinished chunk in parallel. [chunks] is updated in place and [onProgress]
     * receives the sum of bytes done. Each chunk retries with exponential backoff.
     */
    suspend fun download(
        spec: RequestSpec,
        output: OutputTarget,
        chunks: MutableList<ChunkEntity>,
        retries: Int,
        onProgress: (Long) -> Unit,
        onChunkUpdate: suspend (List<ChunkEntity>) -> Unit
    ) = coroutineScope {
        val counters = chunks.map { AtomicLong(it.done) }
        // Immutable ranges: the ticker reads these while the workers update chunks in place.
        val base = chunks.toList()
        val finishedFlags = java.util.concurrent.atomic.AtomicIntegerArray(chunks.size)

        // Progress used to be written only when a whole chunk finished, so with 128 chunks
        // the bar barely moved. Emit a full snapshot every 50 ms (~20 fps) instead.
        val ticker = launch(Dispatchers.IO) {
            while (isActive) {
                delay(50L)
                val snapshot = base.indices.map { i ->
                    base[i].copy(
                        done = counters[i].get(),
                        finished = finishedFlags.get(i) == 1
                    )
                }
                runCatching { onChunkUpdate(snapshot) }
            }
        }

        val jobs = chunks.mapIndexed { i, chunk ->
            async(Dispatchers.IO) {
                if (chunk.finished) return@async
                var attempt = 0
                while (true) {
                    try {
                        copyChunk(spec, output, chunk, counters[i], onProgress)
                        finishedFlags.set(i, 1)
                        chunks[i] = chunk.copy(done = counters[i].get(), finished = true)
                        onChunkUpdate(chunks.toList())
                        return@async
                    } catch (e: IOException) {
                        attempt++
                        if (attempt > retries) throw e
                        delay(min(30_000L, 1000L shl min(attempt, 5)))
                    }
                }
            }
        }
        jobs.awaitAll()
        ticker.cancel()
        onChunkUpdate(chunks.toList())
    }

    private suspend fun copyChunk(
        spec: RequestSpec,
        output: OutputTarget,
        chunk: ChunkEntity,
        counter: AtomicLong,
        onProgress: (Long) -> Unit
    ) {
        val from = chunk.start + counter.get()
        val req = spec.apply(Request.Builder().url(spec.url))
            .header("Range", "bytes=$from-${chunk.end}")
            .build()
        val call: Call = http.newCall(req)
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }

        call.execute().use { resp ->
            if (resp.code != 206) throw IOException("Server ignored range (HTTP ${resp.code})")
            val body = resp.body ?: throw IOException("响应内容为空")
            body.byteStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var pos = from
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n <= 0) break
                    limiter.consume(n)
                    taskLimiter.consume(n)
                    output.write(pos, ByteBuffer.wrap(buf, 0, n))
                    pos += n
                    val done = counter.addAndGet(n.toLong())
                    onProgress(n.toLong())
                    if (done > chunk.end - chunk.start + 1) break
                }
            }
        }
    }

    /** Splits [total] bytes into at most [threads] ranges (256 KB each by default). */
    fun plan(total: Long, threads: Int, minChunk: Long = 1L shl 18): List<ChunkEntity> {
        if (total <= 0L) return listOf(ChunkEntity(0, 0, 0, -1, 0, false))
        val count = min(threads.toLong(), maxOf(1L, total / minChunk)).toInt().coerceAtLeast(1)
        val size = total / count
        return (0 until count).map { i ->
            val start = i * size
            val end = if (i == count - 1) total - 1 else start + size - 1
            ChunkEntity(0, i, start, end, 0, false)
        }
    }
}

/** Request headers a task carries. Applied to every outgoing request for that task. */
data class RequestSpec(
    val url: String,
    val userAgent: String?,
    val referer: String?,
    val cookie: String?
) {
    fun apply(builder: Request.Builder): Request.Builder {
        userAgent?.takeIf { it.isNotBlank() }?.let { builder.header("User-Agent", it) }
        referer?.takeIf { it.isNotBlank() }?.let { builder.header("Referer", it) }
        cookie?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        return builder
    }
}
