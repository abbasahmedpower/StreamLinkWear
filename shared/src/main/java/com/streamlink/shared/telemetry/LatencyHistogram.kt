package com.streamlink.shared.telemetry

/**
 * MICRO-06: Latency Histograms
 * Lock-free, zero-allocation integer bucket array to calculate percentiles.
 */
object LatencyHistogram {

    // Buckets from 0ms to 999ms, plus a catch-all bucket for >= 1000ms
    private const val MAX_LATENCY_MS = 1000
    private val buckets = IntArray(MAX_LATENCY_MS + 1)
    
    @Volatile
    private var totalSamples = 0
    
    @Volatile
    private var worstLatency = 0

    fun record(latencyMs: Int) {
        val clamped = latencyMs.coerceIn(0, MAX_LATENCY_MS)
        buckets[clamped]++
        totalSamples++
        if (latencyMs > worstLatency) {
            worstLatency = latencyMs
        }
    }

    /**
     * Calculates the P50, P90, P95, and P99 percentiles.
     * Returns an array [P50, P90, P95, P99, Worst].
     */
    fun calculatePercentiles(): IntArray {
        val count = totalSamples
        if (count == 0) return intArrayOf(0, 0, 0, 0, worstLatency)

        val targetP50 = (count * 0.50).toInt()
        val targetP90 = (count * 0.90).toInt()
        val targetP95 = (count * 0.95).toInt()
        val targetP99 = (count * 0.99).toInt()

        var currentSum = 0
        var p50 = -1
        var p90 = -1
        var p95 = -1
        var p99 = -1

        for (i in buckets.indices) {
            currentSum += buckets[i]
            
            if (p50 == -1 && currentSum >= targetP50) p50 = i
            if (p90 == -1 && currentSum >= targetP90) p90 = i
            if (p95 == -1 && currentSum >= targetP95) p95 = i
            if (p99 == -1 && currentSum >= targetP99) p99 = i
            
            if (p99 != -1) break
        }

        return intArrayOf(p50, p90, p95, p99, worstLatency)
    }

    /**
     * Clears the histogram (e.g. at the start of a new session).
     */
    fun reset() {
        for (i in buckets.indices) {
            buckets[i] = 0
        }
        totalSamples = 0
        worstLatency = 0
    }
}
