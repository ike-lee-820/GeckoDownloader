package com.ikelee.geckodl.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikelee.geckodl.BuildConfig
import com.ikelee.geckodl.R
import com.ikelee.geckodl.data.settings.AppSettings
import com.ikelee.geckodl.data.settings.SettingsStore
import com.ikelee.geckodl.update.UpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsStore
) : ViewModel() {
    val settings = store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setThreads(v: Int) = viewModelScope.launch { store.setThreads(v) }
    fun setMaxTasks(v: Int) = viewModelScope.launch { store.setMaxTasks(v) }
    fun setRetry(v: Int) = viewModelScope.launch { store.setRetry(v) }
    fun setWifiOnly(v: Boolean) = viewModelScope.launch { store.setWifiOnly(v) }
    fun setDynamic(v: Boolean) = viewModelScope.launch { store.setDynamicColor(v) }
    fun setDark(v: String) = viewModelScope.launch { store.setDarkMode(v) }
    fun setAutoCheck(v: Boolean) = viewModelScope.launch { store.setAutoCheckUpdate(v) }
}

@Composable
fun SettingsScreen(
    updateManager: UpdateManager,
    vm: SettingsViewModel = hiltViewModel()
) {
    val s by vm.settings.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Section(stringResource(R.string.settings_download)) {
            SliderRow(stringResource(R.string.settings_threads), s.threadsPerTask, 1f..32f) { vm.setThreads(it) }
            SliderRow(stringResource(R.string.settings_max_tasks), s.maxConcurrentTasks, 1f..8f) { vm.setMaxTasks(it) }
            SliderRow(stringResource(R.string.settings_retry), s.retryCount, 0f..10f) { vm.setRetry(it) }
        }
        Section(stringResource(R.string.settings_network)) {
            SwitchRow(stringResource(R.string.settings_wifi_only), s.wifiOnly) { vm.setWifiOnly(it) }
        }
        Section(stringResource(R.string.settings_appearance)) {
            SwitchRow(stringResource(R.string.settings_dynamic_color), s.dynamicColor) { vm.setDynamic(it) }
            val modeLabel = when (s.darkMode) {
                "light" -> stringResource(R.string.theme_light)
                "dark" -> stringResource(R.string.theme_dark)
                else -> stringResource(R.string.theme_system)
            }
            Text(stringResource(R.string.settings_theme_mode, modeLabel))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "system" to R.string.theme_system,
                    "light" to R.string.theme_light,
                    "dark" to R.string.theme_dark
                ).forEach { (mode, label) ->
                    FilledTonalButton(onClick = { vm.setDark(mode) }) {
                        Text(stringResource(label))
                    }
                }
            }
        }
        Section(stringResource(R.string.settings_about)) {
            Text("${stringResource(R.string.settings_version)}：${BuildConfig.APP_VERSION}")
            Text(
                stringResource(R.string.settings_update_proxy),
                style = MaterialTheme.typography.bodySmall
            )
            SwitchRow(
                stringResource(R.string.settings_auto_check_update),
                s.autoCheckUpdate
            ) { vm.setAutoCheck(it) }
            FilledTonalButton(
                onClick = { updateManager.checkForUpdate(manual = true) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_check_update))
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Int, range: ClosedFloatingPointRange<Float>, onChange: (Int) -> Unit) {
    Column {
        Text("$label：$value")
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range
        )
    }
}
