package com.streamlink.shared

import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong

/**
 * A lock-free, zero-allocation ring buffer for high-throughput frame streaming.
 * SPSC/MPSC/MPMC safe with commit flags to prevent consumers from reading unwritten slots.
 */
class LockFreeRingBuffer(private val capacity: Int) {
    init {
        require(capacity > 0 && (capacity and (capacity - 1)) == 0) { "Capacity must be a power of 2" }
    }
    private val mask = capacity - 1

    private val buffer = AtomicIntegerArray(capacity)
    private val committed = AtomicIntegerArray(capacity) // 0 = empty/reading, 1 = written & ready
    
    private val head = AtomicLong(0)
    private val tail = AtomicLong(0)

    fun offer(element: Int): Boolean {
        var currentTail: Long
        var nextTail: Long
        var index: Int
        do {
            currentTail = tail.get()
            nextTail = currentTail + 1
            if (nextTail - head.get() > capacity) {
                return false // full
            }
            index = (currentTail and mask.toLong()).toInt()
            // Wait for consumer to clear the commit flag before taking this slot
            if (committed.get(index) == 1) return false
        } while (!tail.compareAndSet(currentTail, nextTail))
        
        // Write the payload FIRST
        buffer.set(index, element)
        // THEN mark as committed (Release)
        committed.set(index, 1)
        return true
    }

    fun poll(): Int? {
        var currentHead: Long
        var index: Int
        var element: Int
        do {
            currentHead = head.get()
            if (currentHead >= tail.get()) {
                return null // empty
            }
            index = (currentHead and mask.toLong()).toInt()
            
            // Wait for producer to commit the payload
            if (committed.get(index) == 0) return null 
            
            element = buffer.get(index)
        } while (!head.compareAndSet(currentHead, currentHead + 1))
        
        // Clear the commit flag for the next producer cycle
        committed.set(index, 0)
        return element
    }

    fun size(): Int = (tail.get() - head.get()).toInt()
}
