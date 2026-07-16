package com.streamlink.shared

import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * A thread-safe pool for ByteBuffers to eliminate Garbage Collection pressure
 * during real-time video streaming.
 */
object ByteBufferPool {
    private const val TAG = "ByteBufferPool"
    // 1MB buffer size is typically enough for a 1080p compressed video frame
    private const val DEFAULT_BUFFER_SIZE = 1024 * 1024 
    private const val MAX_POOL_SIZE = 32

    private val pool = ConcurrentLinkedQueue<ByteBuffer>()
    private val currentSize = AtomicInteger(0)

    /**
     * Acquires a ByteBuffer from the pool, or allocates a new one if the pool is empty.
     */
    fun acquire(requiredCapacity: Int = DEFAULT_BUFFER_SIZE): ByteBuffer {
        // We only pool buffers of DEFAULT_BUFFER_SIZE. 
        // If a larger buffer is requested, we allocate it on the fly (won't be pooled).
        if (requiredCapacity > DEFAULT_BUFFER_SIZE) {
            Log.w(TAG, "Requested buffer size ($requiredCapacity) exceeds pool default. Allocating unpooled.")
            return ByteBuffer.allocateDirect(requiredCapacity)
        }

        val buffer = pool.poll()
        return if (buffer != null) {
            currentSize.decrementAndGet()
            buffer.clear()
            buffer
        } else {
            ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE)
        }
    }

    /**
     * Returns a ByteBuffer to the pool for reuse.
     * ✅ FIX (nano): إصلاح سباق TOCTOU — الكود القديم كان بيعمل
     * check-then-act: لو خيطين وصلوا للشرط سوا وقت currentSize==31،
     * الاتنين بيعدوه وبيتجاوزوا الـ MAX. النمط الجديد: increment أولاً
     * ثم تحقق — لو تجاوزنا الحد، نرجّع العداد ونسيب الـ GC ياخد البفر ده.
     */
    fun release(buffer: ByteBuffer) {
        if (buffer.capacity() != DEFAULT_BUFFER_SIZE) return
        if (currentSize.incrementAndGet() > MAX_POOL_SIZE) {
            currentSize.decrementAndGet() // رجّع العداد، سيب الـ GC ياخد البفر ده
            return
        }
        buffer.clear()
        pool.offer(buffer)
    }

    /**
     * Clears all buffers from the pool.
     */
    fun clear() {
        pool.clear()
        currentSize.set(0)
    }
}
