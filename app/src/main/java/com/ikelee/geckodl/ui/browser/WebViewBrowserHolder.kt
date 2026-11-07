package com.ikelee.geckodl.ui.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.net.toUri
import com.ikelee.geckodl.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/** 一个系统 WebView 标签页的实时状态。 */
data class WebTab(
    val id: String,
    val url: String = "about:blank",
    val title: String = "",
    val progress: Int = 0,
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false
)

/** 嗅探到的一条媒体资源。 */
data class SniffedResource(
    val url: String,
    val kind: String,
    val pageUrl: String,
    val userAgent: String? = null
)

private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

/** 把输入解析成可加载的 URL：裸域名补全 https，其余当搜索词。 */
object UrlResolver {
    fun resolve(input: String, searchEngine: String, customTemplate: String): String {
        val s = input.trim()
        if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("about:")) return s
        if (!s.contains(' ') && s.contains('.') && !s.startsWith(".")) return "https://$s"
        val q = Uri.encode(s)
        if (customTemplate.isNotBlank() && customTemplate.contains("{query}")) {
            return customTemplate.replace("{query}", q)
        }
        return when (searchEngine) {
            "bing" -> "https://www.bing.com/search?q=$q"
            "baidu" -> "https://www.baidu.com/s?wd=$q"
            "duckduckgo" -> "https://duckduckgo.com/?q=$q"
            else -> "https://www.google.com/search?q=$q"
        }
    }
}

/**
 * 用系统 WebView 实现的多标签浏览器（替代原来的 GeckoView 引擎）。
 *
 * 稳定性要点（此前打开页面即闪退的原因）：
 * 1. 所有 WebView 只 attach 一次到同一个 [container]，切换标签只改 visibility，
 *    绝不在 Compose 重组里反复 removeView/addView —— 那会在 WebView 回调栈中改动 View 层级导致崩溃。
 * 2. shouldInterceptRequest 运行在 WebView 的后台线程，绝不在其中调用任何 WebView / WebSettings 方法，
 *    只读 request.requestHeaders（否则抛 CalledFromWrongThreadException）。
 * 3. WebView 用解包后的 Activity context 创建，避免部分 ROM 对 ContextThemeWrapper 不兼容。
 * 4. 客户端回调里通过主 Handler post 一帧再更新 StateFlow，避免在回调栈内同步触发重组。
 */
class WebViewBrowserHolder(
    context: Context,
    private val onDownload: (url: String, userAgent: String?, referer: String?) -> Unit
) {
    /** WebView 需要 Activity context；找不到时退回 applicationContext。 */
    private val webContext: Context = run {
        var c: Context = context
        while (c is ContextWrapper) {
            if (c is Activity) return@run c
            c = c.baseContext
        }
        context.applicationContext
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val views = LinkedHashMap<String, WebView>()
    private val urls = ConcurrentHashMap<String, String>()
    private val pendingViews = ArrayList<Pair<String, WebView>>()
    private var counter = 0

    @Volatile
    private var container: FrameLayout? = null

    @Volatile
    private var sniffEnabled = true

    private val _tabs = MutableStateFlow<List<WebTab>>(emptyList())
    val tabs: StateFlow<List<WebTab>> = _tabs.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _sniffed = MutableStateFlow<List<SniffedResource>>(emptyList())
    val sniffed: StateFlow<List<SniffedResource>> = _sniffed.asStateFlow()

    /** Called once from AndroidView.factory. */
    fun attachContainer(frame: FrameLayout) {
        container = frame
        if (pendingViews.isNotEmpty()) {
            pendingViews.forEach { (id, v) -> addToContainer(id, v) }
            pendingViews.clear()
            syncVisibility()
        }
    }

    private fun addToContainer(id: String, v: WebView) {
        val c = container
        if (c == null) {
            pendingViews += id to v
            return
        }
        if (v.parent !== c) {
            (v.parent as? android.view.ViewGroup)?.removeView(v)
            c.addView(
                v,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    /** 只切可见性，不动 View 层级。每次 Compose 重组都可以安全调用。 */
    fun syncVisibility() {
        val c = container ?: return
        val active = _activeId.value
        for ((id, v) in views) {
            v.visibility = if (id == active) View.VISIBLE else View.GONE
        }
        active?.let { views[it]?.bringToFront() }
    }

    /** 应用设置到所有已创建的 WebView（主线程调用）。 */
    fun applySettings(s: AppSettings) {
        sniffEnabled = s.sniffEnabled
        views.values.forEach { applyTo(it, s) }
    }

    private fun applyTo(view: WebView, s: AppSettings) {
        view.settings.apply {
            javaScriptEnabled = s.javaScriptEnabled
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = if (s.desktopUa) DESKTOP_UA else null
        }
    }

    fun newTab(input: String, s: AppSettings, select: Boolean = true): String {
        val id = "tab-${++counter}"
        val view = WebView(webContext)
        applyTo(view, s)
        sniffEnabled = s.sniffEnabled

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme != "http" && uri.scheme != "https") {
                    runCatching {
                        webContext.startActivity(
                            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    return true
                }
                return false
            }

            override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                urls[id] = url
                postUpdate(id) {
                    it.copy(url = url, loading = true, progress = 0)
                }
            }

            override fun onPageFinished(v: WebView, url: String) {
                urls[id] = url
                // 回调本身在主线程：先取好导航状态，post 的 lambda 里不再碰 WebView。
                val canBack = v.canGoBack()
                val canFwd = v.canGoForward()
                postUpdate(id) {
                    it.copy(
                        url = url,
                        loading = false,
                        progress = 100,
                        canGoBack = canBack,
                        canGoForward = canFwd
                    )
                }
            }

            /**
             * 后台线程回调：只读 request，绝不调用 v.url / v.settings 等 WebView 方法。
             * 返回 null 表示完全交给系统 WebView 自己请求，不做任何拦截。
             */
            override fun shouldInterceptRequest(
                v: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (sniffEnabled) {
                    runCatching {
                        val headers = request.requestHeaders
                        sniff(
                            url = request.url.toString(),
                            pageUrl = headers["Referer"].orEmpty(),
                            ua = headers["User-Agent"]
                        )
                    }
                }
                return null
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, newProgress: Int) {
                postUpdate(id) {
                    it.copy(progress = newProgress, loading = newProgress < 100)
                }
            }

            override fun onReceivedTitle(v: WebView, title: String?) {
                postUpdate(id) { it.copy(title = title.orEmpty()) }
            }
        }

        view.setDownloadListener(DownloadListener { url, userAgent, _, _, _ ->
            onDownload(url, userAgent, urls[id])
        })

        views[id] = view
        addToContainer(id, view)
        _tabs.value = _tabs.value + WebTab(id = id)
        if (select || _activeId.value == null) _activeId.value = id
        syncVisibility()
        view.loadUrl(UrlResolver.resolve(input, s.searchEngine, s.customSearchTemplate))
        return id
    }

    /** 把 StateFlow 更新挪出 WebView 回调栈，避免同步重组操作 View 层级。 */
    private fun postUpdate(id: String, transform: (WebTab) -> WebTab) {
        mainHandler.post {
            _tabs.value = _tabs.value.map { if (it.id == id) transform(it) else it }
        }
    }

    private fun sniff(url: String, pageUrl: String, ua: String?) {
        val path = runCatching { url.toUri().path.orEmpty() }.getOrDefault("")
        val kind = when {
            path.contains(".m3u8", true) || url.contains(".m3u8", true) -> "HLS"
            path.contains(".mp4", true) || url.contains(".mp4", true) -> "MP4"
            path.contains(".mp3", true) || url.contains(".mp3", true) -> "AUDIO"
            path.contains(".m4a", true) || path.contains(".webm", true) ||
                path.contains(".mkv", true) || path.contains(".flv", true) -> "MEDIA"
            path.contains(".mpd", true) -> "DASH"
            else -> return
        }
        // shouldInterceptRequest runs on a WebView background thread; StateFlow.update is atomic.
        _sniffed.update { current ->
            if (current.any { it.url == url }) current
            else (current + SniffedResource(url, kind, pageUrl, ua)).takeLast(100)
        }
    }

    fun clearSniffed() {
        _sniffed.value = emptyList()
    }

    fun select(id: String) {
        if (views.containsKey(id)) {
            _activeId.value = id
            syncVisibility()
        }
    }

    fun close(id: String) {
        val view = views.remove(id) ?: return
        urls.remove(id)
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        mainHandler.post { view.destroy() }
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = remaining
        if (_activeId.value == id) _activeId.value = remaining.lastOrNull()?.id
        syncVisibility()
    }

    private fun activeView(): WebView? = _activeId.value?.let { views[it] }

    fun back() = activeView()?.goBack()
    fun forward() = activeView()?.goForward()
    fun reload() = activeView()?.reload()

    fun load(input: String, s: AppSettings) {
        val v = activeView()
        if (v == null) {
            newTab(input, s)
        } else {
            v.loadUrl(UrlResolver.resolve(input, s.searchEngine, s.customSearchTemplate))
        }
    }

    fun destroyAll() {
        views.values.forEach { v ->
            (v.parent as? android.view.ViewGroup)?.removeView(v)
            mainHandler.post { v.destroy() }
        }
        views.clear()
        urls.clear()
        pendingViews.clear()
        container = null
        _tabs.value = emptyList()
        _activeId.value = null
    }
}
