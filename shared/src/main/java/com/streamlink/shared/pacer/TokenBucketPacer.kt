package com.streamlink.shared.pacer

import kotlin.math.min

/**
 * A non-blocking token bucket algorithm for regulating packet burst rates.
 * Ensures the socket buffer is not overwhelmed and the network thread is not blocked.
 */
class TokenBucketPacer(
    private val targetBytesPerSecond: Long,
    private val maxBurstBytes: Long = targetBytesPerSecond / 10 // Allow 100ms bursts
) {
    private var availableTokens: Double = maxBurstBytes.toDouble()
    private var lastRefillTimestampNanos: Long = System.nanoTime()

    @Synchronized
    fun acquirePermission(packetSizeBytes: Int): Long {
        refillTokens()

        if (availableTokens >= packetSizeBytes) {
            availableTokens -= packetSizeBytes
            return 0L // Allowed to send immediately
        }

        // Calculate wait time in nanoseconds
        val missingBytes = packetSizeBytes - availableTokens
        val waitTimeSeconds = missingBytes / targetBytesPerSecond.toDouble()
        return (waitTimeSeconds * 1_000_000_000).toLong()
    }

    private fun refillTokens() {
        val now = System.nanoTime()
        val elapsedTimeSeconds = (now - lastRefillTimestampNanos) / 1_000_000_000.0
        lastRefillTimestampNanos = now

        val newTokens = elapsedTimeSeconds * targetBytesPerSecond
        availableTokens = min(maxBurstBytes.toDouble(), availableTokens + newTokens)
    }
}
