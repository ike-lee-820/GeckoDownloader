package com.ikelee.geckodl.browser

import java.net.URLEncoder

/** Turns address-bar input into a URL: direct URLs stay as-is, anything else goes to the search engine. */
object UrlResolver {

    private val templates = mapOf(
        "google" to "https://www.google.com/search?q=%s",
        "bing" to "https://www.bing.com/search?q=%s",
        "duckduckgo" to "https://duckduckgo.com/?q=%s",
        "baidu" to "https://www.baidu.com/s?wd=%s"
    )

    fun resolve(input: String, engine: String, customTemplate: String): String {
        val text = input.trim()
        if (text.isEmpty()) return "about:blank"
        if (text.startsWith("http://") || text.startsWith("https://") ||
            text.startsWith("about:") || text.startsWith("file:")
        ) return text
        val looksLikeHost = !text.contains(' ') && text.contains('.')
        if (looksLikeHost) return "https://$text"
        val template = if (engine == "custom" && customTemplate.contains("%s")) customTemplate
        else templates[engine] ?: templates.getValue("google")
        return template.replace("%s", URLEncoder.encode(text, "UTF-8"))
    }
}
