package com.streamlink.shared

import java.util.concurrent.atomic.AtomicLong

/**
 * GOP-Aware Backpressure Drop — NEVER drops I-frames.
 *
 * I-frame (NAL type 5) = keyframe anchor. Dropping it destroys all following P-frames.
 * P-frame (NAL type 1) = safe to drop when queue is above threshold.
 */
object GopFrameDropper {
    private const val QUEUE_HIGH_THRESHOLD = 12  // drop P-frames above this depth
    private const val QUEUE_CRITICAL = 20        // drop aggressively above this

    private val droppedCount = AtomicLong(0)
    private val savedCount = AtomicLong(0)

    /**
     * @param isKeyframe true if I-frame (IDR)
     * @param queueDepth current TCP send queue depth (frames waiting)
     * @return true = drop this frame, false = send it
     */
    fun shouldDrop(isKeyframe: Boolean, queueDepth: Int): Boolean {
        // NEVER drop I-frames — they are the anchor of the GOP
        if (isKeyframe) {
            savedCount.incrementAndGet()
            return false
        }

        return when {
            queueDepth > QUEUE_CRITICAL -> {
                droppedCount.incrementAndGet()
                true  // Aggressive drop — queue is overflowing
            }
            queueDepth > QUEUE_HIGH_THRESHOLD -> {
                // Drop every other P-frame above threshold
                val drop = (droppedCount.get() % 2L == 0L)
                if (drop) droppedCount.incrementAndGet() else savedCount.incrementAndGet()
                drop
            }
            else -> false
        }
    }

    val dropRate: Float
        get() {
            val total = droppedCount.get() + savedCount.get()
            return if (total == 0L) 0f else droppedCount.get().toFloat() / total
        }

    fun reset() {
        droppedCount.set(0)
        savedCount.set(0)
    }
}
