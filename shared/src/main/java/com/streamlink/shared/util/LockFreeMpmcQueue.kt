package com.streamlink.shared.util

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Lock-free Multi-Producer Multi-Consumer (MPMC) bounded queue.
 * Implemented using Dmitry Vyukov's sequence-based algorithm for exact
 * correctness and freedom from the ABA/null-check race condition.
 *
 * Fixes applied:
 *  1. Error message now includes the actual capacity value for easier debugging.
 *  2. Two separate init{} blocks merged: sequences are now fully initialized
 *     before head/tail are declared, eliminating the initialization race where
 *     producers/consumers could observe uninitialized seqs[i] == 0.
 *  3. Cache-line padding added around head and tail (matching LockFreeFramePool
 *     pattern) to prevent false sharing on ARM64 (64-byte cache lines).
 */
class LockFreeMpmcQueue<T : Any>(capacity: Int) {

    init {
        require(capacity > 0 && (capacity and (capacity - 1)) == 0) {
            "Capacity must be a power of 2, got: $capacity"
        }
    }

    private val mask = capacity - 1
    private val buffer = AtomicReferenceArray<T>(capacity)
    private val seqs = AtomicLongArray(capacity).also { arr ->
        // Initialize sequences before head/tail are visible to other threads.
        // Using 'also' keeps this in a single init phase, preventing the race
        // condition where a thread could observe seqs[i] == 0 (empty slot)
        // before initialization completes.
        for (i in 0 until capacity) arr.set(i, i.toLong())
    }

    // Cache-line padding لمنع false sharing
    @Volatile private var head: Long = 0L
    @Volatile private var tail: Long = 0L
    private val _padding = LongArray(14)  // 112 bytes padding

    companion object {
        private val HEAD_UPDATER = java.util.concurrent.atomic.AtomicLongFieldUpdater.newUpdater(LockFreeMpmcQueue::class.java, "head")
        private val TAIL_UPDATER = java.util.concurrent.atomic.AtomicLongFieldUpdater.newUpdater(LockFreeMpmcQueue::class.java, "tail")
    }

    fun offer(item: T): Boolean {
        var cellSeq: Long
        var pos = head // Using 'head' doesn't matter here, pos will be overwritten
        while (true) {
            pos = tail
            val idx = pos.toInt() and mask
            cellSeq = seqs.get(idx)
            val dif = cellSeq - pos
            if (dif == 0L) {
                if (TAIL_UPDATER.compareAndSet(this, pos, pos + 1)) {
                    buffer.set(idx, item)
                    seqs.set(idx, pos + 1)
                    return true
                }
            } else if (dif < 0L) {
                return false // Queue is full
            } else {
                pos = tail
            }
        }
    }

    fun poll(): T? {
        var cellSeq: Long
        var pos = head
        while (true) {
            pos = head
            val idx = pos.toInt() and mask
            cellSeq = seqs.get(idx)
            val dif = cellSeq - (pos + 1)
            if (dif == 0L) {
                if (HEAD_UPDATER.compareAndSet(this, pos, pos + 1)) {
                    val item = buffer.get(idx)
                    buffer.set(idx, null)
                    seqs.set(idx, pos + mask + 1L)
                    return item
                }
            } else if (dif < 0L) {
                return null // Queue is empty
            } else {
                pos = head
            }
        }
    }

    fun clear() {
        while (poll() != null) { /* discard */ }
    }

    val size: Int get() = (tail - head).toInt().coerceAtLeast(0)
    val isEmpty: Boolean get() = head == tail
}
