package com.ikelee.geckodl.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Safety net. Re-queues interrupted tasks and restarts the service when work is waiting.
 * Runs on boot, after a process kill (via the periodic job), and on demand.
 */
@HiltWorker
class RecoveryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: DownloadEngine
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        engine.recoverInterrupted()
        if (engine.hasPendingWork()) DownloadService.start(applicationContext)
        return Result.success()
    }
}

object RecoveryScheduler {
    private const val PERIODIC = "gdl-recovery-periodic"
    private const val ONCE = "gdl-recovery-once"

    fun schedulePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<RecoveryWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun runNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<RecoveryWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, req)
    }
}
