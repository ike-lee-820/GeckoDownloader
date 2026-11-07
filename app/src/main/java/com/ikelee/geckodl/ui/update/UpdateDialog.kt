package com.ikelee.geckodl.ui.update

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ikelee.geckodl.R
import com.ikelee.geckodl.update.UpdateManager
import com.ikelee.geckodl.update.UpdateUiState
import com.ikelee.geckodl.update.formatBytes

@Composable
fun UpdateDialog(manager: UpdateManager) {
    val state by manager.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        manager.events.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    when (val s = state) {
        UpdateUiState.Idle -> Unit

        UpdateUiState.Checking -> AlertDialog(
            onDismissRequest = { manager.dismiss() },
            title = { Text(stringResource(R.string.update_checking)) },
            text = {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator()
                }
            },
            confirmButton = {
                TextButton(onClick = { manager.dismiss() }) {
                    Text(stringResource(R.string.update_cancel))
                }
            }
        )

        is UpdateUiState.Available -> AvailableDialog(s, manager)

        is UpdateUiState.Downloading -> {
            val pct = if (s.total > 0L) {
                (s.downloaded * 100L / s.total).toInt().coerceIn(0, 100)
            } else 0
            AlertDialog(
                onDismissRequest = { /* keep downloading; user must use Cancel */ },
                title = { Text(stringResource(R.string.update_downloading)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(
                                R.string.update_version,
                                "v${manager.currentVersion}",
                                "v${s.release.tag}"
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        LinearProgressIndicator(
                            progress = { pct / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(stringResource(R.string.update_progress,
                            formatBytes(s.downloaded), formatBytes(s.total), pct))
                        Text(stringResource(R.string.update_threads, s.threads),
                            style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.update_proxy_hint),
                            style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { manager.cancelDownload() }) {
                        Text(stringResource(R.string.update_cancel))
                    }
                }
            )
        }

        is UpdateUiState.Ready -> AlertDialog(
            onDismissRequest = { manager.dismiss() },
            title = { Text(stringResource(R.string.update_ready)) },
            text = {
                Text("${s.release.apkName}\n${formatBytes(s.apk.length())}")
            },
            confirmButton = {
                Button(onClick = { manager.install(s.apk) }) {
                    Text(stringResource(R.string.update_install))
                }
            },
            dismissButton = {
                TextButton(onClick = { manager.dismiss() }) {
                    Text(stringResource(R.string.update_cancel))
                }
            }
        )

        is UpdateUiState.Failed -> AlertDialog(
            onDismissRequest = { manager.dismiss() },
            title = { Text(stringResource(R.string.update_failed)) },
            text = { Text(s.message) },
            confirmButton = {
                TextButton(onClick = { manager.retryDownload() }) {
                    Text(stringResource(R.string.update_retry))
                }
            },
            dismissButton = {
                TextButton(onClick = { manager.dismiss() }) {
                    Text(stringResource(R.string.update_cancel))
                }
            }
        )
    }
}

@Composable
private fun AvailableDialog(s: UpdateUiState.Available, manager: UpdateManager) {
    AlertDialog(
        onDismissRequest = { manager.dismiss() },
        title = { Text(stringResource(R.string.update_available)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(
                        R.string.update_version,
                        "v${manager.currentVersion}",
                        "v${s.release.tag}"
                    ),
                    style = MaterialTheme.typography.titleSmall
                )
                if (s.release.apkSize > 0L) {
                    Text(formatBytes(s.release.apkSize),
                        style = MaterialTheme.typography.bodySmall)
                }
                val notes = briefNotes(s.release.notes)
                if (notes.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(notes, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.update_proxy_hint),
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        // Three stacked actions read better than a cramped horizontal row on a phone.
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(onClick = { manager.startInAppDownload() },
                    modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.update_in_app))
                }
                FilledTonalButton(onClick = { manager.openInBrowser(s.release) },
                    modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.update_external))
                }
                TextButton(onClick = { manager.dismiss() },
                    modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.update_cancel))
                }
            }
        },
        dismissButton = null
    )
}

private fun briefNotes(notes: String): String {
    val lines = notes.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .take(5)
    val text = lines.joinToString("\n")
    return if (text.length > 320) text.take(320) + "…" else text
}
