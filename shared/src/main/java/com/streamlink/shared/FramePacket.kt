package com.streamlink.shared

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zero-copy frame wrapper. The ByteBuffer is a view into the encoder's output.
 * Must call release() exactly once to return the codec buffer.
 */
class FramePacket(
    val buffer: ByteBuffer,
    val offset: Int,
    val size: Int,
    val timestampUs: Long,
    val isKeyframe: Boolean,
    private val releaseCallback: () -> Unit
) {
    private val released = AtomicBoolean(false)

    /** Idempotent — safe to call multiple times, releases only once. */
    fun release() {
        if (released.compareAndSet(false, true)) {
            try {
                releaseCallback()
            } catch (_: IllegalStateException) {
                // Codec buffer already released — safe
            }
        }
    }

    val isReleased: Boolean get() = released.get()
}
