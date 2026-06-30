package com.streamlink.shared

import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong

/**
 * A lock-free, zero-allocation ring buffer for high-throughput frame streaming.
 * Nano-level optimization for Hot Path (replaces LockFreeSpscQueue in most critical paths).
 * Uses CAS (Compare-And-Swap) for ultra-low latency queueing.
 */
class LockFreeRingBuffer(capacity: Int) {
    // Capacity must be a power of 2 for fast modulo bitwise operations
    private val mask = capacity - 1
    init {
        require(capacity > 0 && (capacity and (capacity - 1)) == 0) { "Capacity must be a power of 2" }
    }

    private val buffer = AtomicIntegerArray(capacity)
    private val head = AtomicLong(0)
    private val tail = AtomicLong(0)

    fun offer(element: Int): Boolean {
        var currentTail: Long
        var currentHead: Long
        do {
            currentTail = tail.get()
            currentHead = head.get()
            if (currentTail - currentHead >= mask) {
                return false // Buffer full
            }
        } while (!tail.compareAndSet(currentTail, currentTail + 1))
        
        buffer.set((currentTail and mask.toLong()).toInt(), element)
        return true
    }

    fun poll(): Int? {
        var currentHead: Long
        var currentTail: Long
        var element: Int
        do {
            currentHead = head.get()
            currentTail = tail.get()
            if (currentHead >= currentTail) {
                return null // Buffer empty
            }
            element = buffer.get((currentHead and mask.toLong()).toInt())
        } while (!head.compareAndSet(currentHead, currentHead + 1))
        
        return element
    }

    fun size(): Int = (tail.get() - head.get()).toInt()
}
