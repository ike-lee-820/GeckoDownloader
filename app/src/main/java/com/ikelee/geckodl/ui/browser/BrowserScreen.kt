package com.ikelee.geckodl.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikelee.geckodl.R
import com.ikelee.geckodl.browser.GeckoBrowserView
import com.ikelee.geckodl.browser.TabSessionManager
import com.ikelee.geckodl.browser.TabState
import com.ikelee.geckodl.browser.UrlResolver
import com.ikelee.geckodl.data.settings.SettingsStore
import com.ikelee.geckodl.download.DownloadEngine
import com.ikelee.geckodl.download.NewDownload
import com.ikelee.geckodl.sniff.SniffAggregator
import com.ikelee.geckodl.sniff.SniffedResource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BrowserUiState(
    val tabs: List<TabState> = emptyList(),
    val activeId: String? = null,
    val sniffed: List<SniffedResource> = emptyList()
)

@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val tabs: TabSessionManager,
    private val sniff: SniffAggregator,
    private val engine: DownloadEngine,
    private val settingsStore: SettingsStore
) : ViewModel() {

    val state = combine(tabs.tabs, tabs.activeId, sniff.byTab) { t, active, byTab ->
        BrowserUiState(t, active, byTab[active].orEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowserUiState())

    init {
        if (tabs.tabs.value.isEmpty()) {
            viewModelScope.launch {
                val s = settingsStore.settings.first()
                tabs.newTab(
                    url = "https://www.google.com",
                    isPrivate = s.incognitoDefault,
                    userAgent = null,
                    desktop = s.desktopUa
                )
            }
        }
    }

    fun session(id: String?) = id?.let { tabs.session(it) }
    fun select(id: String) = tabs.select(id)
    fun close(id: String) = tabs.close(id)

    fun newTab(isPrivate: Boolean) {
        viewModelScope.launch {
            val s = settingsStore.settings.first()
            tabs.newTab("https://www.google.com", isPrivate, null, s.desktopUa)
        }
    }

    fun submitAddress(input: String) {
        viewModelScope.launch {
            val s = settingsStore.settings.first()
            val url = UrlResolver.resolve(input, s.searchEngine, s.customSearchTemplate)
            tabs.session(tabs.activeId.value ?: return@launch)?.loadUri(url)
        }
    }

    fun back() = tabs.session(tabs.activeId.value ?: return)?.goBack()
    fun forward() = tabs.session(tabs.activeId.value ?: return)?.goForward()
    fun reload() = tabs.session(tabs.activeId.value ?: return)?.reload()

    fun downloadSniffed(resource: SniffedResource) {
        viewModelScope.launch {
            engine.enqueue(
                NewDownload(
                    url = resource.url,
                    referer = resource.pageUrl.ifBlank { null },
                    cookie = resource.headers["Cookie"],
                    userAgent = resource.headers["User-Agent"]
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(vm: BrowserViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val active = state.tabs.firstOrNull { it.id == state.activeId }
    var address by remember(active?.url) { mutableStateOf(active?.url.orEmpty()) }
    var showSniff by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

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
                IconButton(onClick = { vm.back() }) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = stringResource(R.string.browser_back))
                }
                IconButton(onClick = { vm.forward() }) {
                    Icon(Icons.Outlined.ArrowForward, contentDescription = stringResource(R.string.browser_forward))
                }
                IconButton(onClick = { vm.reload() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.browser_reload))
                }
                IconButton(onClick = { showSniff = true }) {
                    BadgedBox(badge = {
                        if (state.sniffed.isNotEmpty()) Badge { Text(state.sniffed.size.toString()) }
                    }) {
                        Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.browser_sniffed))
                    }
                }
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { vm.submitAddress(address) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.browser_address_hint))
            }
            IconButton(onClick = { vm.newTab(false) }) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.browser_new_tab))
            }
            IconButton(onClick = { vm.newTab(true) }) {
                Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.browser_new_private_tab))
            }
        }

        if (active?.loading == true) {
            LinearProgressIndicator(progress = { active.progress / 100f }, modifier = Modifier.fillMaxWidth())
        }

        Box(modifier = Modifier.fillMaxSize()) {
            GeckoBrowserView(session = vm.session(state.activeId))
        }

        // Tab strip: one chip per tab, with close.
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(state.tabs, key = { it.id }) { tab ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { vm.select(tab.id) }, modifier = Modifier.weight(1f)) {
                        Text(
                            (if (tab.isPrivate) "[" + stringResource(R.string.browser_private) + "] " else "") +
                                tab.title.ifBlank { tab.url }
                        )
                    }
                    IconButton(onClick = { vm.close(tab.id) }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.browser_close_tab))
                    }
                }
            }
        }
    }

    if (showSniff) {
        ModalBottomSheet(onDismissRequest = { showSniff = false }, sheetState = sheetState) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.browser_sniffed), style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                if (state.sniffed.isEmpty()) {
                    Text(stringResource(R.string.browser_sniff_empty), modifier = Modifier.padding(top = 12.dp))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.sniffed, key = { it.url }) { res ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(res.kind.name + if (res.segmentCount > 0) " - ${res.segmentCount} segments" else "")
                                Text(res.url, maxLines = 2)
                                FilledTonalButton(onClick = { vm.downloadSniffed(res) }) {
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
