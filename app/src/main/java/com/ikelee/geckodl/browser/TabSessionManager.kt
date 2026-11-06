package com.ikelee.geckodl.browser

import com.ikelee.geckodl.data.db.BrowserDao
import com.ikelee.geckodl.data.db.HistoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebRequestError
import javax.inject.Inject
import javax.inject.Singleton

data class TabState(
    val id: String,
    val url: String = "about:blank",
    val title: String = "",
    val loading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isPrivate: Boolean = false
)

/**
 * Holds one GeckoSession per tab and switches the single GeckoView between them.
 * Sessions stay open while their tab exists, so page state survives tab switches.
 */
@Singleton
class TabSessionManager @Inject constructor(
    private val holder: GeckoRuntimeHolder,
    private val browserDao: BrowserDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = LinkedHashMap<String, GeckoSession>()
    private var counter = 0

    private val _tabs = MutableStateFlow<List<TabState>>(emptyList())
    val tabs: StateFlow<List<TabState>> = _tabs.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    init {
        holder.activeTabKeyProvider = { _activeId.value ?: "default" }
    }

    fun session(id: String): GeckoSession? = sessions[id]

    fun newTab(url: String?, isPrivate: Boolean, userAgent: String?, desktop: Boolean): String {
        val id = "tab-${++counter}"
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(isPrivate)
            .build()
        val session = GeckoSession(settings)
        session.open(holder.runtime)
        if (desktop) session.settings.userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        else userAgent?.let { session.settings.userAgentOverride = it }
        attachDelegates(id, session, isPrivate)
        sessions[id] = session
        _tabs.value = _tabs.value + TabState(id = id, isPrivate = isPrivate)
        _activeId.value = id
        holder.ensureSniffer()
        url?.let { session.loadUri(it) }
        return id
    }

    fun select(id: String) {
        if (sessions.containsKey(id)) _activeId.value = id
    }

    fun close(id: String) {
        val session = sessions.remove(id) ?: return
        session.close()
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = remaining
        if (_activeId.value == id) _activeId.value = remaining.lastOrNull()?.id
        if (remaining.none { it.isPrivate } && session.settings.usePrivateMode) holder.clearPrivateData()
    }

    private fun update(id: String, transform: (TabState) -> TabState) {
        _tabs.value = _tabs.value.map { if (it.id == id) transform(it) else it }
    }

    private fun attachDelegates(id: String, session: GeckoSession, isPrivate: Boolean) {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                val u = url ?: return
                update(id) { it.copy(url = u) }
                if (!isPrivate && u != "about:blank") {
                    scope.launch { browserDao.addHistory(HistoryEntity(url = u, title = u)) }
                }
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                update(id) { it.copy(canGoBack = canGoBack) }
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                update(id) { it.copy(canGoForward = canGoForward) }
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                // Allow everything here. Downloads started from links are handled by the
                // sniffer list and by the system download handler, not by blocking navigation.
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                // window.open and target=_blank. Open it as a new tab and hand GeckoView its session.
                val newId = newTab(uri, isPrivate, null, false)
                return GeckoResult.fromValue(sessions[newId])
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                update(id) { it.copy(loading = false) }
                return null
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                update(id) { it.copy(loading = true, progress = 0, url = url) }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                update(id) { it.copy(loading = false, progress = 100) }
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                update(id) { it.copy(progress = progress) }
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                update(id) { it.copy(title = title.orEmpty()) }
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                // Full screen is not wired to the window yet.
            }
        }
    }
}
