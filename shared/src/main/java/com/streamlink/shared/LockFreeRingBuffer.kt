package com.streamlink.shared

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * A lock-free, zero-allocation ring buffer designed for ultra-low latency.
 * Power of 2 sizes only for fast modulo via bitwise AND.
 */
class LockFreeRingBuffer<T>(capacity: Int) {
    // Ensure capacity is a power of 2
    private val capacity = if ((capacity and (capacity - 1)) == 0) capacity else Integer.highestOneBit(capacity) shl 1
    private val mask = this.capacity - 1

    private val head = AtomicInteger(0)
    private val tail = AtomicInteger(0)
    private val buffer = AtomicReferenceArray<T>(this.capacity)

    /**
     * Tries to offer an element to the queue. Returns true if successful, false if full.
     */
    fun offer(item: T): Boolean {
        var currentTail: Int
        var currentHead: Int
        do {
            currentTail = tail.get()
            currentHead = head.get()
            if (currentTail - currentHead >= capacity) {
                return false // Full
            }
        } while (!tail.compareAndSet(currentTail, currentTail + 1))
        
        buffer.set(currentTail and mask, item)
        return true
    }

    /**
     * Tries to poll an element from the queue. Returns the element if successful, null if empty.
     */
    fun poll(): T? {
        var currentHead: Int
        var currentTail: Int
        var item: T?
        do {
            currentHead = head.get()
            currentTail = tail.get()
            if (currentHead == currentTail) {
                return null // Empty
            }
            item = buffer.get(currentHead and mask)
            if (item == null) {
                // Another thread hasn't finished writing yet, back off slightly
                Thread.yield()
                continue
            }
        } while (!head.compareAndSet(currentHead, currentHead + 1))
        
        // Null out the slot for GC
        buffer.set(currentHead and mask, null)
        return item
    }

    val size: Int
        get() = tail.get() - head.get()
        
    fun isFull(): Boolean = size >= capacity
}
