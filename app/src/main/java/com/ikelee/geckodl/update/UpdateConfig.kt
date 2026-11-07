package com.ikelee.geckodl.update

/**
 * 更新来源配置。
 *
 * Release 元数据走公开的 GitHub API（匿名即可，无需 token）。
 * 用户真正打开/下载的链接统一套用 v6.gh-proxy.com 前缀代理：
 *   https://github.com/xxx  ->  https://v6.gh-proxy.com/github.com/xxx
 * 代理不可用或不支持分段时，[direct] 还原成原始 github.com 地址重试。
 */
object UpdateConfig {
    const val OWNER = "ike-lee-820"
    const val REPO = "GeckoDownloader"

    const val PROXY_HOST = "v6.gh-proxy.com"

    /** 代理前缀：把 https:// 换成 https://v6.gh-proxy.com/ */
    private const val PROXY_PREFIX = "https://$PROXY_HOST/"
    private const val ORIGIN = "https://github.com"
    private const val API_ORIGIN = "https://api.github.com"

    const val LATEST_RELEASE_API = "$API_ORIGIN/repos/$OWNER/$REPO/releases/latest"

    const val USER_AGENT = "GeckoDownloader-UpdateChecker"

    /** 给任意 http(s) 链接加 v6.gh-proxy.com 前缀。已代理过的链接原样返回。 */
    fun proxy(url: String): String = when {
        url.startsWith(PROXY_PREFIX) -> url
        url.startsWith("https://") -> PROXY_PREFIX + url.removePrefix("https://")
        url.startsWith("http://") -> PROXY_PREFIX + url.removePrefix("http://")
        else -> url
    }

    /** 去掉代理前缀，还原为 https:// 直连地址（下载失败时回退用）。 */
    fun direct(url: String): String = when {
        url.startsWith(PROXY_PREFIX) -> "https://" + url.removePrefix(PROXY_PREFIX)
        else -> url
    }

    fun releasePage(tag: String): String = proxy("$ORIGIN/$OWNER/$REPO/releases/tag/$tag")

    fun releasesPage(): String = proxy("$ORIGIN/$OWNER/$REPO/releases")

    /** 代理优先，直连兜底。下载器与浏览器跳转都走这里。 */
    fun candidates(url: String): List<String> = listOf(proxy(url), direct(url)).distinct()
}
