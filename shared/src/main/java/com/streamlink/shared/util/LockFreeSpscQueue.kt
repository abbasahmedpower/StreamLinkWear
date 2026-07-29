package com.streamlink.shared.util

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Lock-free Single-Producer Single-Consumer (SPSC) Queue.
 * Highly optimized for zero-allocation task queuing between two threads.
 * Capacity MUST be a power of 2.
 */
class LockFreeSpscQueue<T : Any>(capacity: Int) {
    init {
        require(capacity > 0 && (capacity and (capacity - 1)) == 0) { "Capacity must be a power of 2" }
    }

    private val mask = (capacity - 1).toLong()
    private val buffer = AtomicReferenceArray<T>(capacity)
    
    // Head is read by consumer, modified by consumer
    // Tail is read by producer, modified by producer
    private val head = AtomicLong(0)
    private val tail = AtomicLong(0)

    @Volatile private var producerTid = -1L
    @Volatile private var consumerTid = -1L

    fun offer(item: T): Boolean {
        if (com.streamlink.shared.BuildConfig.DEBUG) {
            val tid = Thread.currentThread().id
            if (producerTid == -1L) producerTid = tid
            check(producerTid == tid) { "SPSC contract violated: offer() from thread $tid, expected $producerTid" }
        }
        val t = tail.get()
        val h = head.get() // Producer reads consumer's head to check if full
        if (t - h > mask) {
            return false // Full
        }
        val idx = (t and mask).toInt()
        buffer.set(idx, item) // set guarantees memory barrier
        tail.set(t + 1)
        return true
    }

    fun poll(): T? {
        if (com.streamlink.shared.BuildConfig.DEBUG) {
            val tid = Thread.currentThread().id
            if (consumerTid == -1L) consumerTid = tid
            check(consumerTid == tid) { "SPSC contract violated: poll() from thread $tid, expected $consumerTid" }
        }
        val h = head.get()
        val t = tail.get() // Consumer reads producer's tail to check if empty
        if (h == t) {
            return null // Empty
        }
        val idx = (h and mask).toInt()
        val item = buffer.get(idx) ?: return null
        
        // Single-consumer contract: no CAS loop needed.
        head.lazySet(h + 1)
        buffer.set(idx, null)
        return item
    }

    fun clear() {
        while (poll() != null) {
            // Keep polling until empty
        }
    }

    val size: Int
        get() = (tail.get() - head.get()).toInt().coerceAtLeast(0)
        
    val isEmpty: Boolean
        get() = head.get() == tail.get()
}
