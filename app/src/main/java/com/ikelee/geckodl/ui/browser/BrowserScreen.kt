package com.ikelee.geckodl.ui.browser

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikelee.geckodl.R
import com.ikelee.geckodl.data.settings.AppSettings
import com.ikelee.geckodl.data.settings.SettingsStore
import com.ikelee.geckodl.download.DownloadEngine
import com.ikelee.geckodl.download.DownloadService
import com.ikelee.geckodl.download.NewDownload
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val engine: DownloadEngine,
    store: SettingsStore
) : ViewModel() {
    val settings = store.settings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings()
    )

    fun add(url: String, userAgent: String?, referer: String?, context: Context) {
        viewModelScope.launch {
            engine.enqueue(
                NewDownload(url = url, userAgent = userAgent, referer = referer)
            )
            DownloadService.start(context)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(vm: BrowserViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val s by vm.settings.collectAsState()
    val holder = remember {
        WebViewBrowserHolder(context) { url, ua, ref -> vm.add(url, ua, ref, context) }
    }
    DisposableEffect(Unit) { onDispose { holder.destroyAll() } }

    val tabs by holder.tabs.collectAsState()
    val activeId by holder.activeId.collectAsState()
    val sniffed by holder.sniffed.collectAsState()
    val active = tabs.firstOrNull { it.id == activeId }

    var address by remember(active?.url) { mutableStateOf(active?.url.orEmpty()) }
    var showSniff by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    // 首个标签；设置变化实时应用到所有 WebView。
    LaunchedEffect(Unit) {
        if (holder.tabs.value.isEmpty()) {
            holder.newTab("https://www.google.com", s)
        }
    }
    LaunchedEffect(s.javaScriptEnabled, s.desktopUa, s.sniffEnabled, s.searchEngine) {
        holder.applySettings(s)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.browser_address_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            actions = {
                TextButton(onClick = { holder.load(address, s) }) {
                    Text(stringResource(R.string.browser_go))
                }
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { holder.back() }) {
                Icon(Icons.Outlined.ArrowBack, stringResource(R.string.browser_back))
            }
            IconButton(onClick = { holder.forward() }) {
                Icon(Icons.Outlined.ArrowForward, stringResource(R.string.browser_forward))
            }
            IconButton(onClick = { holder.reload() }) {
                Icon(Icons.Outlined.Refresh, stringResource(R.string.browser_reload))
            }
            IconButton(onClick = { holder.newTab("https://www.google.com", s) }) {
                Icon(Icons.Outlined.Add, stringResource(R.string.browser_new_tab))
            }
            IconButton(onClick = { showSniff = true }) {
                BadgedBox(badge = {
                    if (sniffed.isNotEmpty()) Badge { Text(sniffed.size.toString()) }
                }) {
                    Icon(Icons.Outlined.Download, stringResource(R.string.browser_sniffed))
                }
            }
        }

        val progressValue = active?.progress ?: 0
        if (active?.loading == true) {
            LinearProgressIndicator(
                progress = { (progressValue / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 固定容器：WebView 只在创建时 addView 一次，重组时仅同步可见性。
        // 反复 remove/add 正在回调中的 WebView 会造成打开页面闪退。
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    FrameLayout(ctx).also { holder.attachContainer(it) }
                },
                update = { holder.syncVisibility() }
            )
        }

        // 标签条放在 WebView 下方，横向滚动，不挤压网页区域。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { holder.select(tab.id) }) {
                        Text(
                            tab.title.ifBlank { tab.url }.take(18),
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    IconButton(onClick = { holder.close(tab.id) }) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.browser_close_tab))
                    }
                }
            }
        }
    }

    if (showSniff) {
        ModalBottomSheet(onDismissRequest = { showSniff = false }, sheetState = sheetState) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.browser_sniffed),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { holder.clearSniffed() }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
                if (sniffed.isEmpty()) {
                    Text(
                        stringResource(R.string.browser_sniff_empty),
                        modifier = Modifier.padding(top = 12.dp)
                    )
                } else {
                    // 普通 Column + 限高滚动，避免 LazyColumn 在无限高度约束下崩溃。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        sniffed.forEach { res ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text("${res.kind} · ${res.url}", maxLines = 2)
                                FilledTonalButton(onClick = {
                                    vm.add(res.url, res.userAgent, res.pageUrl, context)
                                }) {
                                    Text(stringResource(R.string.browser_sniff_add))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
