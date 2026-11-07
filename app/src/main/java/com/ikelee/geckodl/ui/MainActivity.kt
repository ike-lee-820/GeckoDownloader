package com.ikelee.geckodl.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ikelee.geckodl.R
import com.ikelee.geckodl.ui.browser.BrowserScreen
import com.ikelee.geckodl.ui.downloads.DownloadsScreen
import com.ikelee.geckodl.ui.settings.SettingsScreen
import com.ikelee.geckodl.ui.theme.GdlTheme
import com.ikelee.geckodl.ui.update.UpdateDialog
import com.ikelee.geckodl.update.UpdateManager
import dagger.hilt.android.AndroidEntryPoint
import com.ikelee.geckodl.download.DownloadService
import com.ikelee.geckodl.data.settings.AppSettings
import com.ikelee.geckodl.data.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

private const val ROUTE_DOWNLOADS = "downloads"
private const val ROUTE_BROWSER = "browser"
private const val ROUTE_SETTINGS = "settings"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var updateManager: UpdateManager

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result is read from the system settings if denied; no retry loop */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadService.createChannels(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val settings = hiltViewModel<AppVm>().settings.collectAsState().value
            GdlTheme(dynamicColor = settings.dynamicColor, darkMode = settings.darkMode) {
                AppRoot(updateManager = updateManager)
            }
        }
    }
}

@HiltViewModel
class AppVm @Inject constructor(store: SettingsStore) : ViewModel() {
    val settings = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
}

@Composable
fun AppRoot(updateManager: UpdateManager) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    // Quiet check on every cold start; the dialog only appears when a newer release exists.
    LaunchedEffect(Unit) { updateManager.checkOnStartupIfEnabled() }

    Scaffold(
        bottomBar = {
            NavigationBar {
                BottomItem(nav, current, ROUTE_DOWNLOADS, R.string.tab_downloads, Icons.Outlined.Download)
                BottomItem(nav, current, ROUTE_BROWSER, R.string.tab_browser, Icons.Outlined.Language)
                BottomItem(nav, current, ROUTE_SETTINGS, R.string.tab_settings, Icons.Outlined.Settings)
            }
        }
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = ROUTE_DOWNLOADS,
            modifier = Modifier.fillMaxSize().padding(inner)
        ) {
            composable(ROUTE_DOWNLOADS) { DownloadsScreen() }
            composable(ROUTE_BROWSER) { BrowserScreen() }
            composable(ROUTE_SETTINGS) { SettingsScreen(updateManager = updateManager) }
        }
    }
    // Dialog lives outside the Scaffold so it is shared by every tab.
    UpdateDialog(manager = updateManager)
}

@Composable
private fun BottomItem(
    nav: NavController,
    current: String?,
    route: String,
    label: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    NavigationBarItem(
        selected = current == route,
        onClick = {
            nav.navigate(route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        },
        icon = { Icon(icon, contentDescription = stringResource(label)) },
        label = { Text(stringResource(label)) }
    )
}
