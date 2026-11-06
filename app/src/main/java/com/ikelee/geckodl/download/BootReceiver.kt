package com.ikelee.geckodl.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Runs after reboot. Starts the recovery worker, which re-queues tasks that were
 * running when the device went down. The worker starts the foreground service if needed.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            RecoveryScheduler.runNow(context)
        }
    }
}
