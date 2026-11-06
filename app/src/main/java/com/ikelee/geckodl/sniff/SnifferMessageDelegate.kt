package com.ikelee.geckodl.sniff

import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

/**
 * Receives sendNativeMessage("sniffer", payload) calls from background.js.
 * Each payload is parsed and passed to the aggregator under the active tab key.
 */
class SnifferMessageDelegate(
    private val aggregator: SniffAggregator,
    private val tabKey: () -> String
) : WebExtension.MessageDelegate {

    override fun onMessage(
        nativeApp: String,
        message: Any,
        sender: WebExtension.MessageSender
    ): org.mozilla.geckoview.GeckoResult<Any>? {
        if (message is JSONObject) {
            aggregator.ingest(tabKey(), message)
        }
        return null
    }
}
