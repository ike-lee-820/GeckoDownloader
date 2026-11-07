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

    /** "In-app update": 32 parallel ranged downloads with live progress. */
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
                    // 32 threads can emit hundreds of callbacks per second; throttle recomposition.
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgressEmit >= 80L || done >= total) {
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

    /** "Open in browser": hands the proxied release page to the system default browser. */
    fun openInBrowser(release: ReleaseInfo) {
        val url = UpdateConfig.proxy(
            release.htmlUrl.ifBlank { UpdateConfig.releasePage(release.tag) }
        )
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
