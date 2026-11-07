package com.ikelee.geckodl.update

/**
 * 更新来源配置。
 *
 * Release 元数据走公开的 GitHub API（匿名即可，无需 token）。
 *
 * 重要：gh-proxy 这一类加速服务只代理「文件直链」
 *   https://github.com/{owner}/{repo}/releases/download/{tag}/{file}
 * 不代理 GitHub 网页（/releases/tag/{tag}）。把网页套上代理前缀会打开 GitHub SPA，
 * 而页面的接口/静态资源请求无法经代理完成，于是报
 * "There was an error while loading. Please reload this page."，并且还要再点一次才能下载。
 * 因此应用内所有「打开/下载」动作一律指向 APK 直链，不再指向 Release 网页。
 *
 * 主节点按用户指定为 v6.gh-proxy.com；官方同时提供 V6 节点 v6.gh-proxy.org，
 * 两者都列入候选，下载器逐个探测，谁可用就用谁，最后回退 github.com 直连。
 */
object UpdateConfig {
    const val OWNER = "ike-lee-820"
    const val REPO = "GeckoDownloader"

    /** 主加速节点，用于展示与浏览器跳转。 */
    const val PROXY_HOST = "v6.gh-proxy.com"

    /** 备用节点：官方推荐的国内 / IPv6 线路。 */
    const val PROXY_HOST_FALLBACK = "v6.gh-proxy.org"

    private val PROXY_HOSTS = listOf(PROXY_HOST, PROXY_HOST_FALLBACK)

    private const val ORIGIN = "https://github.com"
    private const val API_ORIGIN = "https://api.github.com"

    const val LATEST_RELEASE_API = "$API_ORIGIN/repos/$OWNER/$REPO/releases/latest"

    const val USER_AGENT = "GeckoDownloader-UpdateChecker"

    private fun prefix(host: String) = "https://$host/"

    /** 给任意 http(s) 链接加代理前缀。已代理过的链接（含备用节点）原样返回。 */
    fun proxy(url: String, host: String = PROXY_HOST): String {
        if (PROXY_HOSTS.any { url.startsWith(prefix(it)) }) return url
        val tail = when {
            url.startsWith("https://") -> url.removePrefix("https://")
            url.startsWith("http://") -> url.removePrefix("http://")
            else -> return url
        }
        return prefix(host) + tail
    }

    /** 去掉任意节点的代理前缀，还原为 https:// 直连地址。 */
    fun direct(url: String): String {
        for (h in PROXY_HOSTS) {
            val p = prefix(h)
            if (url.startsWith(p)) return "https://" + url.removePrefix(p)
        }
        return url
    }

    /** 仅供展示用，不会被应用拿来跳转；网页不走代理。 */
    fun releasePage(tag: String): String = "$ORIGIN/$OWNER/$REPO/releases/tag/$tag"

    /** 主节点代理 → 备用节点代理 → github.com 直连。下载器与浏览器跳转都走这里。 */
    fun candidates(url: String): List<String> =
        (PROXY_HOSTS.map { proxy(url, it) } + direct(url)).distinct()

    /** APK 直链（固定版本），用于浏览器直接下载。 */
    fun apkDirectUrl(tag: String, apkName: String): String =
        "$ORIGIN/$OWNER/$REPO/releases/download/$tag/$apkName"
}
