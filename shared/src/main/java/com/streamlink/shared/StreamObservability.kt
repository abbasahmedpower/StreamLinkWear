package com.streamlink.shared

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight structured observability — no String allocations in hot path.
 * Use conditional logging: if (BuildConfig.DEBUG) StreamObservability.logFrame(...)
 */
object StreamObservability {
    private const val TAG = "SL-Obs"

    private val framesSent = AtomicLong(0L)
    private val dropsTotal = AtomicLong(0L)
    private val reconnectsTotal = AtomicLong(0L)

    fun recordFrameSent() { framesSent.incrementAndGet() }
    fun recordDrop() { dropsTotal.incrementAndGet() }
    fun recordReconnect() { reconnectsTotal.incrementAndGet() }

    fun logSummary() {
        val sent = framesSent.get()
        val drops = dropsTotal.get()
        val total = sent + drops
        val dropPct = if (total == 0L) 0f else drops * 100f / total
        // No string interpolation in hot path — this is called infrequently
        Log.i(TAG, "sent=$sent drops=$drops dropPct=${dropPct.toInt()}% reconnects=${reconnectsTotal.get()}")
    }

    fun reset() {
        framesSent.set(0L)
        dropsTotal.set(0L)
        reconnectsTotal.set(0L)
    }

    val currentDropRate: Float
        get() {
            val total = framesSent.get() + dropsTotal.get()
            return if (total == 0L) 0f else dropsTotal.get().toFloat() / total
        }
}
