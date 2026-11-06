package com.ikelee.geckodl.download

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * HLS downloader. Segments are fetched in parallel into a per-task cache directory,
 * each written as .part then renamed, so an interrupted run resumes by skipping finished files.
 * When all segments exist they are concatenated in order into the output (MPEG-TS).
 * fMP4 init segments (EXT-X-MAP) are written first. Remuxing to MP4 is not performed.
 */
class HlsDownloader(
    private val context: Context,
    private val http: OkHttpClient,
    private val limiter: BandwidthLimiter
) {

    data class Result(val totalBytes: Long, val segments: Int)

    suspend fun run(
        taskId: Long,
        spec: RequestSpec,
        outputUri: String,
        threads: Int,
        retries: Int,
        onSegmentsPlanned: suspend (Int) -> Unit,
        onSegmentDone: suspend (done: Int, total: Int) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val playlist = fetchPlaylist(spec.url, spec)
        val media = when (playlist) {
            is HlsPlaylist.Master -> {
                val best = playlist.variants.firstOrNull()
                    ?: throw IOException("Master playlist has no variants")
                val text = fetchText(best.uri, spec)
                HlsParser.parse(text, best.uri) as? HlsPlaylist.Media
                    ?: throw IOException("Variant is not a media playlist")
            }
            is HlsPlaylist.Media -> playlist
        }
        if (media.segments.isEmpty()) throw IOException("No segments in playlist")

        val cacheDir = File(context.cacheDir, "hls/$taskId").apply { mkdirs() }
        val total = media.segments.size
        onSegmentsPlanned(total)

        val keyCache = HashMap<String, ByteArray>()
        val semaphore = Semaphore(threads.coerceIn(1, 32))
        var done = cacheDir.listFiles()?.count { it.name.endsWith(".seg") } ?: 0
        onSegmentDone(done, total)

        coroutineScope {
            val jobs = media.segments.mapIndexed { i, seg ->
                async(Dispatchers.IO) {
                    val target = File(cacheDir, "%06d.seg".format(i))
                    if (target.exists()) return@async
                    semaphore.withPermit {
                        fetchSegment(seg, i, target, spec, keyCache, retries, media.initUri)
                    }
                    synchronized(this@HlsDownloader) { done++ }
                    onSegmentDone(done, total)
                }
            }
            jobs.awaitAll()
        }

        // Concatenate in order. Init segment (if any) goes first.
        val output = OutputTarget.open(context, outputUri)
        var bytes = 0L
        output.use { out ->
            out.truncate()
            media.initUri?.let { uri ->
                val initFile = File(cacheDir, "init.seg")
                if (!initFile.exists()) downloadRaw(uri, spec, initFile)
                out.appendFrom(initFile)
            }
            for (i in 0 until total) {
                val f = File(cacheDir, "%06d.seg".format(i))
                out.appendFrom(f)
            }
            bytes = out.sizeBytes()
        }
        cacheDir.deleteRecursively()
        Result(bytes, total)
    }

    private suspend fun fetchPlaylist(url: String, spec: RequestSpec): HlsPlaylist =
        HlsParser.parse(fetchText(url, spec), url)

    private suspend fun fetchText(url: String, spec: RequestSpec): String = withContext(Dispatchers.IO) {
        val req = spec.apply(Request.Builder().url(url)).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for playlist")
            resp.body?.string() ?: throw IOException("Empty playlist")
        }
    }

    private suspend fun downloadRaw(url: String, spec: RequestSpec, target: File) {
        val part = File(target.parentFile, target.name + ".part")
        withContext(Dispatchers.IO) {
            http.newCall(spec.apply(Request.Builder().url(url)).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for segment")
                resp.body!!.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n <= 0) break
                            limiter.consume(n)
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
        }
        part.renameTo(target)
    }

    private suspend fun fetchSegment(
        seg: HlsSegment,
        index: Int,
        target: File,
        spec: RequestSpec,
        keyCache: MutableMap<String, ByteArray>,
        retries: Int,
        initUri: String?
    ) {
        var attempt = 0
        while (true) {
            try {
                val raw = fetchBytes(seg.uri, spec)
                val data = if (seg.key != null && seg.key.method == "AES-128") {
                    val keyUri = seg.key.uri ?: throw IOException("AES key URI missing")
                    val key = keyCache.getOrPut(keyUri) { fetchBytes(keyUri, spec) }
                    decryptAes128(raw, key, seg.key.iv ?: sequenceIv(seg.sequence))
                } else raw
                val part = File(target.parentFile, target.name + ".part")
                part.writeBytes(data)
                if (!part.renameTo(target)) throw IOException("Rename failed for segment $index")
                return
            } catch (e: IOException) {
                attempt++
                if (attempt > retries) throw e
                delay(min(30_000L, 1000L shl min(attempt, 5)))
            }
        }
    }

    private suspend fun fetchBytes(url: String, spec: RequestSpec): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(spec.apply(Request.Builder().url(url)).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            val bytes = resp.body!!.bytes()
            limiter.consume(bytes.size)
            bytes
        }
    }

    private fun sequenceIv(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        for (i in 0 until 8) iv[15 - i] = ((sequence ushr (8 * i)) and 0xFF).toByte()
        return iv
    }

    private fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }
}
