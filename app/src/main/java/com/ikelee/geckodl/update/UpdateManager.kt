package com.ikelee.geckodl.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import com.ikelee.geckodl.BuildConfig
import com.ikelee.geckodl.R
import com.ikelee.geckodl.data.settings.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for update state, shared by the startup check and the Settings screen.
 *
 * Startup checks are quiet (no dialog unless a newer release exists). Manual checks report
 * "up to date" / errors through [events] as a toast.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val checker: UpdateChecker,
    private val downloader: ApkDownloader,
    private val store: SettingsStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var lastProgressEmit = 0L

    val currentVersion: String get() = BuildConfig.APP_VERSION

    /** Called once from MainActivity.onCreate. Respects the Settings toggle. */
    fun checkOnStartupIfEnabled() {
        scope.launch {
            if (store.settings.first().autoCheckUpdate) checkForUpdate(manual = false)
        }
    }

    fun checkForUpdate(manual: Boolean) {
        val current = _state.value
        if (current is UpdateUiState.Downloading || current is UpdateUiState.Ready) return
        checkJob?.cancel()
        checkJob = scope.launch {
            if (manual) _state.value = UpdateUiState.Checking
            try {
                val info = checker.latest()
                if (info == null) {
                    if (manual) toast(context.getString(R.string.toast_no_release))
                    _state.value = UpdateUiState.Idle
                    return@launch
                }
                if (!isNewerVersion(info.tag, currentVersion)) {
                    if (manual) toast(context.getString(R.string.toast_up_to_date, currentVersion))
                    _state.value = UpdateUiState.Idle
                    return@launch
                }
                _state.value = UpdateUiState.Available(info, manual)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (manual) toast(context.getString(
                    R.string.toast_check_failed,
                    e.message ?: context.getString(R.string.error_network)
                ))
                _state.value = UpdateUiState.Idle
            }
        }
    }

    /** "In-app update": up to 128 parallel ranged downloads with live progress. */
    fun startInAppDownload() {
        val release = (_state.value as? UpdateUiState.Available)?.release ?: return
        downloadJob?.cancel()
        lastProgressEmit = 0L
        downloadJob = scope.launch {
            val dest = File(context.filesDir, "updates/${release.apkName}")
            _state.value = UpdateUiState.Downloading(
                release = release,
                downloaded = 0L,
                total = release.apkSize.coerceAtLeast(1L),
                threads = ApkDownloader.THREADS
            )
            try {
                val file = downloader.downloadWithFallback(
                    urls = UpdateConfig.candidates(release.apkUrl),
                    dest = dest,
                    threads = ApkDownloader.THREADS
                ) { done, total, threads ->
                    // 128 threads emit thousands of callbacks per second. Throttle to ~60 fps
                    // (16 ms) so the progress bar stays responsive without flooding Compose.
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgressEmit >= 16L || done >= total) {
                        lastProgressEmit = now
                        _state.value = UpdateUiState.Downloading(release, done, total, threads)
                    }
                }
                _state.value = UpdateUiState.Ready(release, file)
            } catch (e: CancellationException) {
                // cancelDownload() already reset the state.
            } catch (e: Exception) {
                _state.value = UpdateUiState.Failed(
                    e.message ?: context.getString(R.string.error_download), release
                )
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        val release = when (val s = _state.value) {
            is UpdateUiState.Downloading -> s.release
            else -> null
        }
        _state.value = if (release != null) UpdateUiState.Available(release, true)
        else UpdateUiState.Idle
    }

    /**
     * "应用外更新"：交给系统默认浏览器的是一个 **APK 文件直链**，打开即开始下载。
     *
     * 以前这里跳的是 release.htmlUrl（/releases/tag/... 网页）再套代理前缀，
     * 但 gh-proxy 只代理文件、不代理 GitHub 网页，浏览器会停在 GitHub 的
     * "There was an error while loading" 页面上，根本到不了下载按钮。
     * 现在先探测各加速节点，挑第一个真正返回文件的直链再跳转。
     */
    fun openInBrowser(release: ReleaseInfo) {
        scope.launch {
            val apkUrl = release.apkUrl.ifBlank {
                UpdateConfig.apkDirectUrl(release.tag, release.apkName)
            }
            val targets = UpdateConfig.candidates(apkUrl)
            val url = try {
                downloader.firstWorking(targets)
            } catch (e: Exception) {
                UpdateConfig.direct(apkUrl)
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                _state.value = UpdateUiState.Idle
                toast(context.getString(R.string.toast_opening_browser))
            } catch (e: ActivityNotFoundException) {
                _state.value = UpdateUiState.Failed(
                    context.getString(R.string.error_no_browser), release
                )
            }
        }
    }

    /** Launches the package installer for the downloaded APK. */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        }
        try {
            context.startActivity(intent)
            _state.value = UpdateUiState.Idle
        } catch (e: ActivityNotFoundException) {
            _state.value = UpdateUiState.Failed(
                context.getString(R.string.error_no_installer), null
            )
        }
    }

    fun retryDownload() {
        val release = (_state.value as? UpdateUiState.Failed)?.release
        if (release != null) {
            _state.value = UpdateUiState.Available(release, true)
            startInAppDownload()
        } else {
            checkForUpdate(manual = true)
        }
    }

    fun dismiss() {
        checkJob?.cancel()
        _state.value = UpdateUiState.Idle
    }

    private fun toast(message: String) {
        _events.tryEmit(message)
    }
}
