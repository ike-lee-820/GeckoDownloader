package com.ikelee.geckodl.update

/**
 * Where updates come from.
 *
 * Release metadata is read from the public GitHub API. No token is needed and none is stored:
 * releases and their assets are public, and api.github.com allows anonymous reads.
 *
 * Links that a user actually opens or downloads are rewritten to the v6.github.com proxy.
 * If the proxy fails, [direct] restores the original github.com URL and the downloader retries.
 */
object UpdateConfig {
    const val OWNER = "ike-lee-820"
    const val REPO = "GeckoDownloader"

    const val PROXY_HOST = "v6.github.com"

    private const val ORIGIN = "https://github.com"
    private const val PROXY = "https://$PROXY_HOST"
    private const val API_ORIGIN = "https://api.github.com"

    const val LATEST_RELEASE_API = "$API_ORIGIN/repos/$OWNER/$REPO/releases/latest"
    const val RELEASES_PAGE = "$PROXY/$OWNER/$REPO/releases"

    const val USER_AGENT = "GeckoDownloader-UpdateChecker"

    /** github.com -> v6.github.com. Already-proxied and non-GitHub URLs are returned as-is. */
    fun proxy(url: String): String = when {
        url.startsWith(PROXY) -> url
        url.startsWith(ORIGIN) -> PROXY + url.removePrefix(ORIGIN)
        url.startsWith("http://github.com") -> PROXY + url.removePrefix("http://github.com")
        else -> url
    }

    /** v6.github.com -> github.com (fallback when the proxy is unreachable). */
    fun direct(url: String): String = when {
        url.startsWith(PROXY) -> ORIGIN + url.removePrefix(PROXY)
        else -> url
    }

    fun releasePage(tag: String): String = "$PROXY/$OWNER/$REPO/releases/tag/$tag"

    /** Proxy first, original host second. Used by both the downloader and the browser intent. */
    fun candidates(url: String): List<String> = listOf(proxy(url), direct(url)).distinct()
}
