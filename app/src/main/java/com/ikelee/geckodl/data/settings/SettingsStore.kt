package com.ikelee.geckodl.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    // Download
    val threadsPerTask: Int = 4,
    val maxConcurrentTasks: Int = 3,
    val retryCount: Int = 3,
    val globalLimitKbps: Int = 0,          // 0 means unlimited
    val fileNameTemplate: String = "{name}",
    val downloadDirUri: String? = null,    // null means app-specific Downloads folder
    val finishAction: String = "notify",   // notify | open | none
    // Network
    val defaultUaPreset: String = "chrome",
    val customUserAgent: String = "",
    val refererPolicy: String = "page",    // page | none
    val wifiOnly: Boolean = false,
    // Browser
    val searchEngine: String = "google",
    val customSearchTemplate: String = "",
    val desktopUa: Boolean = false,
    val javaScriptEnabled: Boolean = true,
    val sniffEnabled: Boolean = true,
    val incognitoDefault: Boolean = false,
    // Appearance
    val dynamicColor: Boolean = true,
    val themeColorSeed: Long = 0xFF1B5E20,
    val darkMode: String = "system",       // system | light | dark
    // Updates
    val autoCheckUpdate: Boolean = true,
    // Privacy
    val logReportingEnabled: Boolean = false
)

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val ds = context.settingsDataStore

    private object Keys {
        val threads = intPreferencesKey("threads")
        val maxTasks = intPreferencesKey("max_tasks")
        val retry = intPreferencesKey("retry")
        val globalLimit = intPreferencesKey("global_limit_kbps")
        val nameTemplate = stringPreferencesKey("name_template")
        val downloadDir = stringPreferencesKey("download_dir")
        val finishAction = stringPreferencesKey("finish_action")
        val uaPreset = stringPreferencesKey("ua_preset")
        val customUa = stringPreferencesKey("custom_ua")
        val refererPolicy = stringPreferencesKey("referer_policy")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val searchEngine = stringPreferencesKey("search_engine")
        val customSearch = stringPreferencesKey("custom_search")
        val desktopUa = booleanPreferencesKey("desktop_ua")
        val js = booleanPreferencesKey("js_enabled")
        val sniff = booleanPreferencesKey("sniff_enabled")
        val incognito = booleanPreferencesKey("incognito_default")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val seed = longPreferencesKey("theme_seed")
        val darkMode = stringPreferencesKey("dark_mode")
        val autoCheck = booleanPreferencesKey("auto_check_update")
        val logs = booleanPreferencesKey("log_reporting")
    }

    val settings: Flow<AppSettings> = ds.data.map { p ->
        val d = AppSettings()
        AppSettings(
            threadsPerTask = (p[Keys.threads] ?: d.threadsPerTask).coerceIn(1, 128),
            maxConcurrentTasks = (p[Keys.maxTasks] ?: d.maxConcurrentTasks).coerceIn(1, 32),
            retryCount = (p[Keys.retry] ?: d.retryCount).coerceIn(0, 10),
            globalLimitKbps = p[Keys.globalLimit] ?: d.globalLimitKbps,
            fileNameTemplate = p[Keys.nameTemplate] ?: d.fileNameTemplate,
            downloadDirUri = p[Keys.downloadDir],
            finishAction = p[Keys.finishAction] ?: d.finishAction,
            defaultUaPreset = p[Keys.uaPreset] ?: d.defaultUaPreset,
            customUserAgent = p[Keys.customUa] ?: d.customUserAgent,
            refererPolicy = p[Keys.refererPolicy] ?: d.refererPolicy,
            wifiOnly = p[Keys.wifiOnly] ?: d.wifiOnly,
            searchEngine = p[Keys.searchEngine] ?: d.searchEngine,
            customSearchTemplate = p[Keys.customSearch] ?: d.customSearchTemplate,
            desktopUa = p[Keys.desktopUa] ?: d.desktopUa,
            javaScriptEnabled = p[Keys.js] ?: d.javaScriptEnabled,
            sniffEnabled = p[Keys.sniff] ?: d.sniffEnabled,
            incognitoDefault = p[Keys.incognito] ?: d.incognitoDefault,
            dynamicColor = p[Keys.dynamic] ?: d.dynamicColor,
            themeColorSeed = p[Keys.seed] ?: d.themeColorSeed,
            darkMode = p[Keys.darkMode] ?: d.darkMode,
            autoCheckUpdate = p[Keys.autoCheck] ?: d.autoCheckUpdate,
            logReportingEnabled = p[Keys.logs] ?: d.logReportingEnabled
        )
    }

    suspend fun setThreads(value: Int) = ds.edit { it[Keys.threads] = value.coerceIn(1, 128) }
    suspend fun setMaxTasks(value: Int) = ds.edit { it[Keys.maxTasks] = value.coerceIn(1, 32) }
    suspend fun setRetry(value: Int) = ds.edit { it[Keys.retry] = value.coerceIn(0, 10) }
    suspend fun setGlobalLimit(value: Int) = ds.edit { it[Keys.globalLimit] = value.coerceAtLeast(0) }
    suspend fun setWifiOnly(value: Boolean) = ds.edit { it[Keys.wifiOnly] = value }
    suspend fun setDownloadDir(uri: String?) = ds.edit {
        if (uri == null) it.remove(Keys.downloadDir) else it[Keys.downloadDir] = uri
    }
    suspend fun setSearchEngine(value: String) = ds.edit { it[Keys.searchEngine] = value }
    suspend fun setCustomSearch(value: String) = ds.edit { it[Keys.customSearch] = value }
    suspend fun setDesktopUa(value: Boolean) = ds.edit { it[Keys.desktopUa] = value }
    suspend fun setSniffEnabled(value: Boolean) = ds.edit { it[Keys.sniff] = value }
    suspend fun setJavaScript(value: Boolean) = ds.edit { it[Keys.js] = value }
    suspend fun setIncognitoDefault(value: Boolean) = ds.edit { it[Keys.incognito] = value }
    suspend fun setDynamicColor(value: Boolean) = ds.edit { it[Keys.dynamic] = value }
    suspend fun setDarkMode(value: String) = ds.edit { it[Keys.darkMode] = value }
    suspend fun setAutoCheckUpdate(value: Boolean) = ds.edit { it[Keys.autoCheck] = value }
    suspend fun setUaPreset(value: String) = ds.edit { it[Keys.uaPreset] = value }
    suspend fun setCustomUa(value: String) = ds.edit { it[Keys.customUa] = value }
    suspend fun setLogReporting(value: Boolean) = ds.edit { it[Keys.logs] = value }
}
