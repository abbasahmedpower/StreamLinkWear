package com.streamlink.shared.metrics

import java.util.Arrays
import java.util.concurrent.atomic.AtomicInteger

class MicroLatencyProfiler(private val bufferSize: Int = 2000) {
    // Fixed size primitive array to avoid Auto-boxing and GC allocation completely
    private val latencyBuffer = LongArray(bufferSize)
    private val writeIndex = AtomicInteger(0)

    /**
     * Record time sample with nanosecond precision.
     * @param startNanoTime The time captured at the start of the operation by System.nanoTime()
     */
    fun recordSample(startNanoTime: Long) {
        val currentLatency = System.nanoTime() - startNanoTime
        val index = (writeIndex.getAndIncrement() and Int.MAX_VALUE) % bufferSize
        latencyBuffer[index] = currentLatency
    }

    /**
     * Calculate desired Percentile (e.g. 95 or 99) in microseconds precision and convert to milliseconds
     */
    fun getPercentile(percentile: Double): Double {
        // Fast local clone to avoid locks (Lock-Free Thread Safety)
        val snapshot = latencyBuffer.clone()
        snapshot.sort() // Ascending order

        val targetIndex = (percentile / 100.0 * (snapshot.size - 1)).toInt()
        val latencyInNanoseconds = snapshot[targetIndex]
        
        // Convert ns to ms with fractional precision
        return latencyInNanoseconds / 1_000_000.0
    }

    fun reset() {
        writeIndex.set(0)
        Arrays.fill(latencyBuffer, 0L)
    }
}
