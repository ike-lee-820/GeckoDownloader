package com.ikelee.geckodl.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateChecker @Inject constructor(private val http: OkHttpClient) {

    /** The shared download client has no read timeout; metadata calls get their own short one. */
    private val client: OkHttpClient = http.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Returns null when the repo has no published release yet. */
    suspend fun latest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(UpdateConfig.LATEST_RELEASE_API)
            .header("User-Agent", UpdateConfig.USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()

        client.newCall(request).execute().use { resp ->
            if (resp.code == 404) return@withContext null
            if (!resp.isSuccessful) throw IOException("GitHub API returned HTTP ${resp.code}")
            val json = resp.body?.string().orEmpty()
            if (json.isBlank()) throw IOException("Empty response from GitHub API")
            parse(json)
        }
    }

    private fun parse(json: String): ReleaseInfo? {
        val root = JSONObject(json)
        val tag = root.optString("tag_name").trim()
        if (tag.isEmpty()) return null

        val assets = root.optJSONArray("assets")
        var apkUrl = ""
        var apkName = ""
        var apkSize = 0L

        // Prefer the arm64-v8a build the release workflow publishes; fall back to any APK.
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                val url = a.optString("browser_download_url")
                if (url.isBlank()) continue
                val size = a.optLong("size", 0L)
                val isArm64 = name.contains("arm64-v8a", ignoreCase = true)
                if (apkUrl.isEmpty() || (isArm64 && !apkName.contains("arm64-v8a", ignoreCase = true))) {
                    apkUrl = url
                    apkName = name
                    apkSize = size
                }
                if (isArm64) break
            }
        }
        if (apkUrl.isEmpty()) return null

        return ReleaseInfo(
            tag = tag,
            name = root.optString("name").ifBlank { tag },
            notes = root.optString("body"),
            publishedAt = root.optString("published_at"),
            htmlUrl = root.optString("html_url").ifBlank { UpdateConfig.releasePage(tag) },
            apkUrl = apkUrl,
            apkName = apkName,
            apkSize = apkSize
        )
    }
}
