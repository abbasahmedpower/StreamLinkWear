package com.streamlink.shared.ai

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Cache-line-friendly digital twin using flat primitive atomics — zero Float boxing on hot path.
 *
 * flatMetrics layout:
 * [0] reactionTimeMs, [1] velocityX, [2] accelerationX, [3] confidence
 */
class AlignedUserTwin {
    private val flatMetrics = Array(4) { AtomicLong(0L) }
    private val totalProcessedFrames = AtomicInteger(0)

    fun updateMetricsRaw(velocity: Float, acceleration: Float, confidence: Float) {
        flatMetrics[1].set(floatToBits(velocity))
        flatMetrics[2].set(floatToBits(acceleration))
        flatMetrics[3].set(floatToBits(confidence.coerceIn(0f, 1f)))
        totalProcessedFrames.incrementAndGet()
    }

    fun updateReactionTimeMs(reactionMs: Float) {
        flatMetrics[0].set(floatToBits(reactionMs.coerceAtLeast(0f)))
    }

    fun getConfidence(): Float = bitsToFloat(flatMetrics[3].get())

    fun getVelocity(): Float = bitsToFloat(flatMetrics[1].get())

    fun getAcceleration(): Float = bitsToFloat(flatMetrics[2].get())

    fun getReactionTimeMs(): Float = bitsToFloat(flatMetrics[0].get())

    fun getProcessedFrameCount(): Int = totalProcessedFrames.get()

    private fun floatToBits(value: Float): Long =
        java.lang.Float.floatToIntBits(value).toLong()

    private fun bitsToFloat(bits: Long): Float =
        java.lang.Float.intBitsToFloat(bits.toInt())
}
