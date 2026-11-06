package com.ikelee.geckodl.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.ikelee.geckodl.download.DownloadEngine
import com.ikelee.geckodl.download.DownloadService
import com.ikelee.geckodl.download.NewDownload
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Entry point for external links, shared text and the custom ADD_DOWNLOAD action.
 * Creates a task and returns immediately. No UI is shown.
 */
@AndroidEntryPoint
class DownloadRequestActivity : ComponentActivity() {

    @Inject
    lateinit var engine: DownloadEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = extractUrl(intent)
        if (url == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            engine.enqueue(NewDownload(url = url, referer = intent?.getStringExtra("referer")))
            DownloadService.start(this@DownloadRequestActivity)
            finish()
        }
    }

    private fun extractUrl(intent: Intent?): String? {
        intent ?: return null
        val raw = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            "com.ikelee.geckodl.action.ADD_DOWNLOAD" -> intent.getStringExtra("url")
            else -> null
        } ?: return null
        val candidate = Regex("https?://\\S+|ftp://\\S+").find(raw)?.value ?: return null
        return candidate
    }
}
