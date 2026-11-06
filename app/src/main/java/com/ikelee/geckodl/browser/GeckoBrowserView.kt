package com.ikelee.geckodl.browser

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * Compose wrapper around the single GeckoView. The active session is swapped in on
 * recomposition, so each tab keeps its own page state.
 */
@Composable
fun GeckoBrowserView(
    session: GeckoSession?,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx: Context -> GeckoView(ctx) },
        update = { view ->
            if (session != null && view.session !== session) {
                view.setSession(session)
            }
        }
    )
}
