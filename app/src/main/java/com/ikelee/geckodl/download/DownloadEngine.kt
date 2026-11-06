package com.ikelee.geckodl.download

import android.content.Context
import com.ikelee.geckodl.data.db.ChunkDao
import com.ikelee.geckodl.data.db.ChunkEntity
import com.ikelee.geckodl.data.db.DownloadDao
import com.ikelee.geckodl.data.db.DownloadEntity
import com.ikelee.geckodl.data.db.DownloadKind
import com.ikelee.geckodl.data.db.DownloadStatus
import com.ikelee.geckodl.data.settings.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class NewDownload(
    val url: String,
    val fileName: String? = null,
    val referer: String? = null,
    val cookie: String? = null,
    val userAgent: String? = null,
    val kind: DownloadKind? = null,
    val priority: Int = 0
)

/** Live state for the UI. Speed is bytes per second, computed every tick. */
data class LiveState(val speedBytesPerSec: Long = 0L)

/**
 * Owns download execution. The UI never touches this directly; it reads Room flows and
 * [activeCount] / [speeds]. Tasks are scheduled up to the configured global concurrency.
 */
@Singleton
class DownloadEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloads: DownloadDao,
    private val chunks: ChunkDao,
    private val http: OkHttpClient,
    private val settings: SettingsStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val jobs = HashMap<Long, Job>()
    private val globalLimiter = BandwidthLimiter()

    // Bytes received per task since start, and the value at the previous speed sample.
    private val counters = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicLong>()
    private val lastSample = HashMap<Long, Long>()

    private fun counterFor(id: Long) =
        counters.getOrPut(id) { java.util.concurrent.atomic.AtomicLong() }

    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    private val _speeds = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val speeds: StateFlow<Map<Long, Long>> = _speeds.asStateFlow()

    val tasks: Flow<List<DownloadEntity>> = downloads.observeAll()

    init {
        scope.launch { settings.settings.collect { globalLimiter.bytesPerSecond = it.globalLimitKbps * 1024L } }
        scope.launch { schedule() }
        // Speed sampler: once a second, bytes received since the last sample, per task.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000L)
                val next = HashMap<Long, Long>()
                for ((id, counter) in counters) {
                    val now = counter.get()
                    next[id] = now - (lastSample[id] ?: 0L)
                    lastSample[id] = now
                }
                _speeds.value = next
            }
        }
    }

    suspend fun enqueue(request: NewDownload): Long {
        val s = settings.settings.first()
        val kind = request.kind ?: if (request.url.contains(".m3u8", ignoreCase = true)) DownloadKind.HLS else DownloadKind.HTTP
        val name = request.fileName ?: guessName(request.url, kind)
        val entity = DownloadEntity(
            url = request.url,
            referer = if (s.refererPolicy == "none") null else request.referer,
            cookie = request.cookie,
            userAgent = request.userAgent ?: resolveUa(s.defaultUaPreset, s.customUserAgent, s.desktopUa),
            kind = kind,
            fileName = applyTemplate(s.fileNameTemplate, name),
            outputUri = "",
            threads = s.threadsPerTask,
            priority = request.priority
        )
        val id = downloads.insert(entity)
        val output = OutputTarget.allocate(context, s.downloadDirUri, entity.fileName, defaultDir())
        downloads.update(downloads.get(id)!!.copy(outputUri = output))
        schedule()
        return id
    }

    suspend fun pause(id: Long) = mutex.withLock {
        jobs.remove(id)?.cancel()
        downloads.get(id)?.let { downloads.update(it.copy(status = DownloadStatus.PAUSED, updatedAt = now())) }
    }

    suspend fun resume(id: Long) {
        downloads.get(id)?.let {
            if (it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELED) {
                downloads.update(it.copy(status = DownloadStatus.QUEUED, error = null, updatedAt = now()))
            }
        }
        schedule()
    }

    suspend fun retry(id: Long) = resume(id)

    suspend fun cancel(id: Long) = mutex.withLock {
        jobs.remove(id)?.cancel()
        downloads.get(id)?.let {
            downloads.update(it.copy(status = DownloadStatus.CANCELED, updatedAt = now()))
        }
    }

    suspend fun remove(id: Long) {
        cancel(id)
        counters.remove(id)
        lastSample.remove(id)
        chunks.clear(id)
        downloads.delete(id)
    }

    suspend fun setPriority(id: Long, priority: Int) {
        downloads.get(id)?.let { downloads.update(it.copy(priority = priority, updatedAt = now())) }
    }

    /** Re-queues anything that was running when the process died. Called by boot and recovery. */
    suspend fun recoverInterrupted() {
        downloads.withStatus(listOf(DownloadStatus.RUNNING)).forEach {
            downloads.update(it.copy(status = DownloadStatus.QUEUED, updatedAt = now()))
        }
        schedule()
    }

    suspend fun hasPendingWork(): Boolean =
        downloads.withStatus(listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING)).isNotEmpty()

    private suspend fun schedule() = mutex.withLock {
        val max = settings.settings.first().maxConcurrentTasks
        while (jobs.size < max) {
            val next = downloads.withStatus(listOf(DownloadStatus.QUEUED))
                .firstOrNull { it.id !in jobs } ?: break
            downloads.update(next.copy(status = DownloadStatus.RUNNING, updatedAt = now()))
            jobs[next.id] = scope.launch { execute(next.id) }
        }
        _activeCount.value = jobs.size
    }

    private suspend fun execute(id: Long) {
        val task = downloads.get(id) ?: return
        val limiter = BandwidthLimiter()
        val s = settings.settings.first()
        val spec = RequestSpec(task.url, task.userAgent, task.referer, task.cookie)
        try {
            when (task.kind) {
                DownloadKind.HTTP -> runHttp(task, spec, limiter, s.retryCount)
                DownloadKind.HLS -> runHls(task, spec, limiter, s.retryCount)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutex.withLock {
                downloads.get(id)?.let {
                    downloads.update(it.copy(status = DownloadStatus.FAILED, error = e.message ?: e.javaClass.simpleName, updatedAt = now()))
                }
                jobs.remove(id)
                _activeCount.value = jobs.size
            }
            schedule()
            return
        }
        mutex.withLock {
            jobs.remove(id)
            _activeCount.value = jobs.size
        }
        schedule()
    }

    private suspend fun runHttp(task: DownloadEntity, spec: RequestSpec, limiter: BandwidthLimiter, retries: Int) {
        val counter = counterFor(task.id)
        val downloader = HttpDownloader(http, globalLimiter, limiter)
        var current = task
        var stored = chunks.forTask(task.id)
        if (stored.isEmpty()) {
            val probe = downloader.probe(spec)
            val plan = downloader.plan(probe.totalBytes, if (probe.rangeSupported) task.threads else 1)
                .map { it.copy(taskId = task.id) }
            stored = plan
            chunks.upsertAll(stored)
            current = current.copy(totalBytes = probe.totalBytes)
            downloads.update(current)
        }
        val list = stored.toMutableList()
        val output = OutputTarget.open(context, task.outputUri)
        output.use { out ->
            out.ensureSize(current.totalBytes)
            downloader.download(
                spec = spec,
                output = out,
                chunks = list,
                retries = retries,
                onProgress = { delta -> counter.addAndGet(delta) },
                onChunkUpdate = { snapshot ->
                    chunks.upsertAll(snapshot.map { it.copy(taskId = task.id) })
                    val done = snapshot.sumOf { it.done }
                    downloads.get(task.id)?.let { row ->
                        downloads.update(row.copy(downloadedBytes = done, updatedAt = now()))
                    }
                }
            )
        }
        val finalTotal = if (current.totalBytes > 0) current.totalBytes else list.sumOf { it.done }
        val row = downloads.get(task.id) ?: return
        if (current.totalBytes > 0 && list.sumOf { it.done } != current.totalBytes) {
            throw IOException("Size mismatch: expected ${current.totalBytes}")
        }
        downloads.update(row.copy(status = DownloadStatus.COMPLETED, downloadedBytes = finalTotal, totalBytes = finalTotal, updatedAt = now()))
    }

    private suspend fun runHls(task: DownloadEntity, spec: RequestSpec, limiter: BandwidthLimiter, retries: Int) {
        val counter = counterFor(task.id)
        val hls = HlsDownloader(context, http, globalLimiter) { n -> counter.addAndGet(n.toLong()) }
        val result = hls.run(
            taskId = task.id,
            spec = spec,
            outputUri = task.outputUri,
            threads = task.threads,
            retries = retries,
            onSegmentsPlanned = { total ->
                downloads.get(task.id)?.let { downloads.update(it.copy(segmentsTotal = total, updatedAt = now())) }
            },
            onSegmentDone = { done, total ->
                downloads.get(task.id)?.let {
                    downloads.update(it.copy(segmentsDone = done, segmentsTotal = total, updatedAt = now()))
                }
            }
        )
        downloads.get(task.id)?.let {
            downloads.update(it.copy(status = DownloadStatus.COMPLETED, totalBytes = result.totalBytes, downloadedBytes = result.totalBytes, updatedAt = now()))
        }
    }

    private fun defaultDir(): File =
        context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir

    private fun guessName(url: String, kind: DownloadKind): String {
        val raw = url.substringBefore('?').substringAfterLast('/').ifBlank { "download" }
        return if (kind == DownloadKind.HLS && !raw.endsWith(".ts")) raw.substringBeforeLast('.') + ".ts" else raw
    }

    private fun applyTemplate(template: String, name: String): String {
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        return template.replace("{name}", base).replace("{ext}", ext.removePrefix(".")).let {
            if (it.contains('.')) it else it + ext
        }
    }

    private fun resolveUa(preset: String, custom: String, desktop: Boolean): String? = when {
        preset == "custom" && custom.isNotBlank() -> custom
        desktop -> "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
        preset == "safari" -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"
        preset == "ios" -> "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
        preset == "android" -> "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
        else -> "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    }

    private fun now() = System.currentTimeMillis()
}
