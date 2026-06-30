package com.streamlink.shared.testing

import android.util.Log

/**
 * Measures true round-trip time (RTT) overhead of the Android processing layers
 * to guarantee that the Hot Path remains under 10ms.
 */
object LatencyBenchmark {
    private const val TAG = "LatencyBenchmark"

    fun measureHotPathLatency(iterations: Int = 1000, operation: () -> Unit) {
        Log.i(TAG, "Starting Latency Benchmark ($iterations iterations)...")
        
        var totalTimeNs = 0L
        var maxTimeNs = 0L

        // Warmup (JIT compilation)
        for (i in 0 until 100) {
            operation()
        }

        // Benchmark
        for (i in 0 until iterations) {
            val startNs = System.nanoTime()
            operation()
            val elapsedNs = System.nanoTime() - startNs

            totalTimeNs += elapsedNs
            if (elapsedNs > maxTimeNs) {
                maxTimeNs = elapsedNs
            }
        }

        val avgTimeMs = (totalTimeNs / iterations) / 1_000_000.0
        val maxTimeMs = maxTimeNs / 1_000_000.0

        Log.i(TAG, "Benchmark Complete! Avg Latency: $avgTimeMs ms | Max Latency (Spike): $maxTimeMs ms")
        
        if (avgTimeMs > 10.0) {
            Log.w(TAG, "⚠️ WARNING: Hot path average latency ($avgTimeMs ms) exceeds the 10ms target!")
        } else {
            Log.i(TAG, "✅ SUCCESS: Hot path is ultra-fast and within the 10ms threshold.")
        }
    }
}
