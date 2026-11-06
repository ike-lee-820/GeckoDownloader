package com.ikelee.geckodl.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.FloatingActionButton
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
import com.ikelee.geckodl.data.db.DownloadEntity
import com.ikelee.geckodl.data.db.DownloadStatus
import com.ikelee.geckodl.download.DownloadEngine
import com.ikelee.geckodl.download.DownloadService
import com.ikelee.geckodl.download.NewDownload
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DownloadsUiState(
    val items: List<DownloadEntity> = emptyList(),
    val speeds: Map<Long, Long> = emptyMap(),
    val loaded: Boolean = false
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val engine: DownloadEngine
) : ViewModel() {

    val state: StateFlow<DownloadsUiState> = combine(engine.tasks, engine.speeds) { items, speeds ->
        DownloadsUiState(items = items, speeds = speeds, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    fun add(url: String, context: android.content.Context) {
        viewModelScope.launch {
            engine.enqueue(NewDownload(url = url.trim()))
            DownloadService.start(context)
        }
    }

    fun pause(id: Long) = viewModelScope.launch { engine.pause(id) }
    fun resume(id: Long) = viewModelScope.launch { engine.resume(id) }
    fun cancel(id: Long) = viewModelScope.launch { engine.cancel(id) }
    fun remove(id: Long) = viewModelScope.launch { engine.remove(id) }
}

@Composable
fun DownloadsScreen(vm: DownloadsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.downloads_add))
            }
        }
    ) { inner ->
        when {
            !state.loaded -> Column(
                modifier = Modifier.fillMaxSize().padding(inner),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }

            state.items.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize().padding(inner).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { Text(stringResource(R.string.downloads_empty)) }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.items, key = { it.id }) { item ->
                    DownloadCard(
                        item = item,
                        speed = state.speeds[item.id] ?: 0L,
                        onPause = { vm.pause(item.id) },
                        onResume = { vm.resume(item.id) },
                        onCancel = { vm.cancel(item.id) },
                        onRemove = { vm.remove(item.id) }
                    )
                }
            }
        }
    }

    if (showAdd) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.downloads_add)) },
            text = {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.downloads_url_hint)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (text.startsWith("http")) vm.add(text, context)
                    showAdd = false
                }) { Text(stringResource(R.string.action_add)) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.action_cancel_dialog)) }
            }
        )
    }
}

@Composable
private fun DownloadCard(
    item: DownloadEntity,
    speed: Long,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.fileName, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            val fraction = if (item.totalBytes > 0) item.downloadedBytes.toFloat() / item.totalBytes else 0f
            if (item.kind == com.ikelee.geckodl.data.db.DownloadKind.HLS && item.segmentsTotal > 0) {
                LinearProgressIndicator(
                    progress = { item.segmentsDone.toFloat() / item.segmentsTotal },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Segments ${item.segmentsDone}/${item.segmentsTotal}")
            } else {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text("${formatBytes(item.downloadedBytes)} / ${if (item.totalBytes > 0) formatBytes(item.totalBytes) else "?"}  ${formatBytes(speed)}/s")
            }
            Text(
                text = item.status.name.lowercase().replaceFirstChar { it.uppercase() } +
                    (item.error?.let { " - $it" } ?: ""),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (item.status) {
                    DownloadStatus.RUNNING, DownloadStatus.QUEUED -> FilledTonalButton(onClick = onPause) {
                        Icon(Icons.Outlined.Pause, contentDescription = null)
                        Text(" " + stringResource(R.string.action_pause))
                    }
                    DownloadStatus.PAUSED -> FilledTonalButton(onClick = onResume) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Text(" " + stringResource(R.string.action_resume))
                    }
                    DownloadStatus.FAILED, DownloadStatus.CANCELED -> FilledTonalButton(onClick = onResume) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Text(" " + stringResource(R.string.action_retry))
                    }
                    DownloadStatus.COMPLETED -> Unit
                }
                if (item.status != DownloadStatus.COMPLETED) {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_cancel))
                    }
                } else {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_cancel))
                    }
                }
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return "%.1f %s".format(v, units[i])
}
