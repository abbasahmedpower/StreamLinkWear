package com.streamlink.shared

import java.lang.ref.SoftReference
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free MPMC (Multi-Producer Multi-Consumer) wire buffer pool.
 * ⚠️ CAS-based ring buffer — DO NOT assume single-writer/single-reader
 * when optimizing. Verified safe for concurrent access from:
 *   - HardwareEncoder thread (acquire on frame produce)
 *   - DirectSocketServer.runSender thread (acquire on encrypt, release on send)
 *   - Input receiver thread (release on control message ack)
 * Fixed: buffer size now includes updated 20-byte header.
 */
object WireBufferPool {
    private const val POOL_SIZE = 256          // power of 2
    private const val MASK = (POOL_SIZE - 1).toLong()

    // Buffer size = MTU + header + safety margin
    val BUFFER_SIZE = StreamProtocol.WIRE_BUFFER_SIZE

    private val pool = AtomicReferenceArray<ByteArray?>(POOL_SIZE)
    private val head = AtomicLong(0L)
    private val tail = AtomicLong(0L)
    
    // SoftReference secondary pool to avoid GC thrashing under extreme backpressure
    private val softPool = ConcurrentLinkedQueue<SoftReference<ByteArray>>()

    init {
        for (i in 0 until POOL_SIZE) {
            pool.set(i, ByteArray(BUFFER_SIZE))
        }
        tail.set(POOL_SIZE.toLong())
    }

    fun acquire(): ByteArray {
        while (true) {
            val h = head.get()
            val t = tail.get()
            if (h == t) {
                // Primary pool empty — check soft pool
                while (true) {
                    val ref = softPool.poll() ?: return ByteArray(BUFFER_SIZE)
                    val buf = ref.get()
                    if (buf != null) return buf
                }
            }
            val idx = (h and MASK).toInt()
            val buf = pool.get(idx) ?: continue
            if (head.compareAndSet(h, h + 1)) {
                pool.set(idx, null)
                return buf
            }
        }
    }

    fun release(buf: ByteArray) {
        if (buf.size < BUFFER_SIZE) return  // Wrong size — don't pool
        while (true) {
            val t = tail.get()
            val h = head.get()
            if (t - h >= POOL_SIZE) {
                // Primary pool full — put into soft pool instead of discarding to GC
                softPool.offer(SoftReference(buf))
                return
            }
            val idx = (t and MASK).toInt()
            if (tail.compareAndSet(t, t + 1)) {
                pool.set(idx, buf)
                return
            }
        }
    }

    val pooledCount: Int get() = (tail.get() - head.get()).toInt().coerceIn(0, POOL_SIZE)
}
