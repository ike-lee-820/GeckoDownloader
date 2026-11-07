package com.ikelee.geckodl.ui.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import com.ikelee.geckodl.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * 每个标签持有自己的 WebView 实例，切换时只做 attach/detach，因此页面状态保留。
 * 下载链接交给 DownloadEngine；媒体资源通过 shouldInterceptRequest 被动嗅探，
 * 不拦截、不代理任何请求。
 */
class WebViewBrowserHolder(
    private val context: Context,
    private val onDownload: (url: String, userAgent: String?, referer: String?) -> Unit
) {
    private val views = LinkedHashMap<String, WebView>()
    private var counter = 0
    private var sniffEnabled = true

    private val _tabs = MutableStateFlow<List<WebTab>>(emptyList())
    val tabs: StateFlow<List<WebTab>> = _tabs.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _sniffed = MutableStateFlow<List<SniffedResource>>(emptyList())
    val sniffed: StateFlow<List<SniffedResource>> = _sniffed.asStateFlow()

    fun view(id: String?): WebView? = id?.let { views[it] }

    /** 应用设置到所有已创建的 WebView。 */
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
        val view = WebView(context)
        applyTo(view, s)
        sniffEnabled = s.sniffEnabled

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme != "http" && uri.scheme != "https") {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    return true
                }
                return false
            }

            override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                update(id) { it.copy(url = url, loading = true, progress = 0) }
            }

            override fun onPageFinished(v: WebView, url: String) {
                update(id) {
                    it.copy(
                        url = url,
                        loading = false,
                        progress = 100,
                        canGoBack = v.canGoBack(),
                        canGoForward = v.canGoForward()
                    )
                }
            }

            override fun shouldInterceptRequest(
                v: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (sniffEnabled) sniff(request.url.toString(), v.url, v.settings.userAgentString)
                // 返回 null：完全交给系统 WebView 自己请求，不做任何拦截。
                return null
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, newProgress: Int) {
                update(id) { it.copy(progress = newProgress, loading = newProgress < 100) }
            }

            override fun onReceivedTitle(v: WebView, title: String?) {
                update(id) { it.copy(title = title.orEmpty()) }
            }
        }

        view.setDownloadListener(DownloadListener { url, userAgent, _, _, _ ->
            onDownload(url, userAgent, view.url)
        })

        views[id] = view
        _tabs.value = _tabs.value + WebTab(id = id)
        if (select || _activeId.value == null) _activeId.value = id
        view.loadUrl(UrlResolver.resolve(input, s.searchEngine, s.customSearchTemplate))
        return id
    }

    private fun sniff(url: String, pageUrl: String?, ua: String?) {
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
        val current = _sniffed.value
        if (current.any { it.url == url }) return
        _sniffed.value = (current + SniffedResource(url, kind, pageUrl.orEmpty(), ua)).takeLast(100)
    }

    fun clearSniffed() {
        _sniffed.value = emptyList()
    }

    fun select(id: String) {
        if (views.containsKey(id)) _activeId.value = id
    }

    fun close(id: String) {
        val view = views.remove(id) ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = remaining
        if (_activeId.value == id) _activeId.value = remaining.lastOrNull()?.id
    }

    fun activeView(): WebView? = _activeId.value?.let { views[it] }

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
        views.values.forEach {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        views.clear()
        _tabs.value = emptyList()
        _activeId.value = null
    }

    private fun update(id: String, transform: (WebTab) -> WebTab) {
        _tabs.value = _tabs.value.map { if (it.id == id) transform(it) else it }
    }
}
