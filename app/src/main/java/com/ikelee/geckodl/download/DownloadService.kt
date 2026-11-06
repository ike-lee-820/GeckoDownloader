package com.ikelee.geckodl.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ikelee.geckodl.R
import com.ikelee.geckodl.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service (dataSync) that stays up while any download is active. The persistent
 * notification shows the active task count. It is stopped as soon as the engine goes idle.
 */
@AndroidEntryPoint
class DownloadService : Service() {

    @Inject
    lateinit var engine: DownloadEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification(0))
        if (watcher == null) {
            watcher = scope.launch {
                engine.activeCount.combine(engine.speeds) { count, _ -> count }.collect { count ->
                    if (count == 0 && !engine.hasPendingWork()) {
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(count))
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
    }

    private fun buildNotification(active: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(getString(R.string.notif_service_title))
            .setContentText(if (active == 0) "Waiting" else "$active active")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_SERVICE = "download_service"
        const val CHANNEL_RESULT = "download_result"

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            context.startForegroundService(intent)
        }

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SERVICE, context.getString(R.string.notif_channel_service), NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RESULT, context.getString(R.string.notif_channel_result), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }
}
