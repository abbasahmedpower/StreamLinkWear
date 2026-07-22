package com.streamlink.shared.transport

import android.util.Log
import kotlin.math.max

/**
 * Adaptive Packet Pacer using a dynamic Token Bucket algorithm.
 * Controls the burstiness of the network traffic to prevent overflowing router buffers
 * and to keep Jitter low, similar to QUIC's BBR.
 */
class AdaptivePacketPacer(
    private val maxTokens: Int = 100, // Maximum burst
    private val defaultRefillRateMs: Int = 10 // Refill 1 token every X ms
) {
    private var tokens = maxTokens.toFloat()
    private var lastRefillTimeNanos = System.nanoTime()
    
    // Adaptive parameters
    @Volatile
    private var currentRefillRateNanos = defaultRefillRateMs * 1_000_000L

    /**
     * Updates the pacing rate based on network telemetry.
     */
    fun adapt(rttMs: Int, jitterMs: Int, packetLossPercent: Float, queueDepth: Int) {
        // Base refill rate increases (slower pacing) if network is stressed
        var targetRefillMs = defaultRefillRateMs.toFloat()
        
        if (packetLossPercent > 5f) {
            targetRefillMs *= 2f // Slow down by 2x if loss is > 5%
        } else if (rttMs > 100) {
            targetRefillMs *= 1.5f
        }
        
        if (queueDepth > 10) {
            targetRefillMs *= 1.5f
        }

        // Apply EWMA to smooth transitions
        val currentRefillMs = currentRefillRateNanos / 1_000_000f
        val smoothedRefillMs = currentRefillMs * 0.8f + targetRefillMs * 0.2f
        
        currentRefillRateNanos = (smoothedRefillMs * 1_000_000L).toLong()
    }

    /**
     * Tries to acquire a token to send a packet. Returns true if allowed, false if paced.
     */
    fun tryAcquireToken(): Boolean {
        refill()
        if (tokens >= 1f) {
            tokens -= 1f
            return true
        }
        return false
    }

    private fun refill() {
        val now = System.nanoTime()
        val elapsed = now - lastRefillTimeNanos
        
        if (elapsed > currentRefillRateNanos) {
            val tokensToAdd = elapsed.toFloat() / currentRefillRateNanos.toFloat()
            tokens = minOf(maxTokens.toFloat(), tokens + tokensToAdd)
            lastRefillTimeNanos = now
        }
    }
}
