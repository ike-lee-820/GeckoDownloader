package com.ikelee.geckodl.browser

import android.content.Context
import com.ikelee.geckodl.sniff.SniffAggregator
import com.ikelee.geckodl.sniff.SnifferMessageDelegate
import dagger.hilt.android.qualifiers.ApplicationContext
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single GeckoRuntime and the built-in sniffer extension. Private browsing uses
 * private-mode sessions on the same runtime; [clearPrivateData] wipes its storage on exit.
 */
@Singleton
class GeckoRuntimeHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sniffAggregator: SniffAggregator
) {
    val runtime: GeckoRuntime by lazy {
        GeckoRuntime.create(
            context,
            GeckoRuntimeSettings.Builder()
                .consoleOutput(false)
                .build()
        )
    }

    @Volatile
    private var sniffer: WebExtension? = null

    /** Callback for which tab key should own the sniffed resources. Set by the tab manager. */
    @Volatile
    var activeTabKeyProvider: () -> String = { "default" }

    /**
     * Installs the sniffer once, from assets. ensureBuiltIn avoids a second install on
     * process restart. The message delegate routes results to the aggregator.
     */
    fun ensureSniffer(onInstalled: (WebExtension) -> Unit = {}) {
        if (sniffer != null) return
        runtime.webExtensionController
            .ensureBuiltIn("resource://android/assets/sniffer/", "sniffer@geckodl.ikelee")
            .accept({ ext ->
                if (ext != null) {
                    sniffer = ext
                    ext.setMessageDelegate(
                        SnifferMessageDelegate(sniffAggregator) { activeTabKeyProvider() },
                        "sniffer"
                    )
                    onInstalled(ext)
                }
            }, { error -> android.util.Log.w("GDL", "Sniffer install failed", error) })
    }

    /** Wipes cookies, cache and site storage for the private session. Call when leaving private mode. */
    fun clearPrivateData() {
        runtime.storageController.clearData(StorageController.ClearFlags.ALL)
    }
}
