package com.streamlink.shared

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * MetricsCollector — Real stream performance metrics aggregator.
 *
 * Tracks: frames sent, frames dropped, bytes, FPS, drop rate.
 * Flushes metrics to EventPipeline at a fixed interval.
 */
class MetricsCollector(private val scope: CoroutineScope) {
    private var job: Job? = null

    private val framesSent   = AtomicLong(0L)
    private val framesDropped = AtomicLong(0L)
    private val bytesSent    = AtomicLong(0L)
    private val lastFlushMs  = AtomicLong(System.currentTimeMillis())

    fun recordFrame(size: Int) {
        framesSent.incrementAndGet()
        bytesSent.addAndGet(size.toLong())
    }

    fun recordDrop() {
        framesDropped.incrementAndGet()
    }

    /** Current FPS since last flush */
    fun currentFps(): Int {
        val elapsed = (System.currentTimeMillis() - lastFlushMs.get()).coerceAtLeast(1L)
        return ((framesSent.get() * 1000.0) / elapsed).roundToInt()
    }

    /** Drop rate 0.0–1.0 */
    fun dropRate(): Float {
        val total = framesSent.get() + framesDropped.get()
        return if (total == 0L) 0f else framesDropped.get().toFloat() / total.toFloat()
    }

    /** Throughput in KB/s */
    fun throughputKbps(): Long {
        val elapsed = (System.currentTimeMillis() - lastFlushMs.get()).coerceAtLeast(1L)
        return (bytesSent.get() * 8 * 1000) / (elapsed * 1024)
    }

    fun start() {
        job = scope.launch {
            while (true) {
                delay(StreamProtocol.METRICS_FLUSH_INTERVAL_MS)
                flush()
            }
        }
    }

    private fun flush() {
        val fps      = currentFps()
        val drop     = dropRate()
        val kbps     = throughputKbps()
        // Reset counters for next window
        framesSent.set(0L)
        framesDropped.set(0L)
        bytesSent.set(0L)
        lastFlushMs.set(System.currentTimeMillis())

        // Push to GlobalStreamState so UI reflects real values
        scope.launch {
            GlobalStreamState.update {
                copy(fps = fps, bitrateKbps = kbps.toInt())
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
