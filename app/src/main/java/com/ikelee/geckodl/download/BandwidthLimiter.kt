package com.ikelee.geckodl.download

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

/**
 * Simple byte-rate limiter. consume() sleeps when the caller is ahead of the allowed rate.
 * A limit of 0 disables throttling.
 */
class BandwidthLimiter(bytesPerSecond: Long = 0L) {

    @Volatile
    var bytesPerSecond: Long = bytesPerSecond

    private val windowStart = AtomicLong(System.nanoTime())
    private val consumed = AtomicLong(0L)

    suspend fun consume(bytes: Int) {
        val limit = bytesPerSecond
        if (limit <= 0L) return
        val total = consumed.addAndGet(bytes.toLong())
        val elapsedMs = (System.nanoTime() - windowStart.get()) / 1_000_000L
        val allowedMs = total * 1000L / limit
        if (allowedMs > elapsedMs) delay(allowedMs - elapsedMs)
        // Reset the window every 5 seconds so a long pause does not build up a burst.
        if (elapsedMs > 5_000L) {
            windowStart.set(System.nanoTime())
            consumed.set(0L)
        }
    }
}
