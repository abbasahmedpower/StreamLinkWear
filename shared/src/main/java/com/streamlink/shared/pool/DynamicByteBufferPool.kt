package com.streamlink.shared.pool

import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * A multi-tier ByteBuffer pool designed to prevent BufferOverflowExceptions
 * and OutOfMemoryErrors during large Keyframes, while maintaining Zero-GC
 * for the vast majority of frames.
 */
object DynamicByteBufferPool {
    private const val SMALL_BUFFER_SIZE = 64 * 1024
    private const val LARGE_BUFFER_SIZE = 512 * 1024
    private const val MAX_POOL_CAPACITY = 16

    private val smallPool = ArrayDeque<ByteBuffer>()
    private val largePool = ArrayDeque<ByteBuffer>()

    @Synchronized
    fun acquire(capacityNeeded: Int): ByteBuffer {
        val pool = if (capacityNeeded <= SMALL_BUFFER_SIZE) smallPool else largePool
        val buffer = if (pool.isNotEmpty()) {
            val b = pool.removeLast()
            b.clear()
            b
        } else {
            val targetCapacity = if (capacityNeeded <= SMALL_BUFFER_SIZE) SMALL_BUFFER_SIZE else maxOf(LARGE_BUFFER_SIZE, capacityNeeded)
            ByteBuffer.allocateDirect(targetCapacity)
        }
        return buffer
    }

    @Synchronized
    fun release(buffer: ByteBuffer) {
        buffer.clear()
        when {
            buffer.capacity() == SMALL_BUFFER_SIZE && smallPool.size < MAX_POOL_CAPACITY -> {
                smallPool.addLast(buffer)
            }
            buffer.capacity() >= LARGE_BUFFER_SIZE && largePool.size < MAX_POOL_CAPACITY -> {
                largePool.addLast(buffer)
            }
            // If the pool is full, the buffer will be garbage collected automatically
        }
    }

    /**
     * Inline Helper to ensure safe usage and guaranteed recycling via try-finally.
     */
    inline fun <R> useBuffer(capacityNeeded: Int, block: (ByteBuffer) -> R): R {
        val buffer = acquire(capacityNeeded)
        return try {
            block(buffer)
        } finally {
            release(buffer)
        }
    }
}
