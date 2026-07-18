package com.streamlink.shared.util

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Lock-free Multi-Producer Multi-Consumer (MPMC) bounded queue.
 * Implemented using Dmitry Vyukov's sequence-based algorithm for exact
 * correctness and freedom from the ABA/null-check race condition.
 */
class LockFreeMpmcQueue<T : Any>(capacity: Int) {
    init {
        require(capacity > 0 && (capacity and (capacity - 1)) == 0) {
            "Capacity must be a power of 2"
        }
    }

    private val mask = capacity - 1
    private val buffer = AtomicReferenceArray<T>(capacity)
    private val seqs = AtomicLongArray(capacity)
    
    init {
        for (i in 0 until capacity) {
            seqs.set(i, i.toLong())
        }
    }

    private val head = AtomicLong(0)
    private val tail = AtomicLong(0)

    fun offer(item: T): Boolean {
        var cellSeq: Long
        var pos = tail.get()
        while (true) {
            pos = tail.get()
            val idx = pos.toInt() and mask
            cellSeq = seqs.get(idx)
            val dif = cellSeq - pos
            if (dif == 0L) {
                if (tail.compareAndSet(pos, pos + 1)) {
                    buffer.set(idx, item)
                    seqs.set(idx, pos + 1)
                    return true
                }
            } else if (dif < 0L) {
                return false // Queue is full
            } else {
                pos = tail.get()
            }
        }
    }

    fun poll(): T? {
        var cellSeq: Long
        var pos = head.get()
        while (true) {
            pos = head.get()
            val idx = pos.toInt() and mask
            cellSeq = seqs.get(idx)
            val dif = cellSeq - (pos + 1)
            if (dif == 0L) {
                if (head.compareAndSet(pos, pos + 1)) {
                    val item = buffer.get(idx)
                    buffer.set(idx, null)
                    seqs.set(idx, pos + mask + 1L)
                    return item
                }
            } else if (dif < 0L) {
                return null // Queue is empty
            } else {
                pos = head.get()
            }
        }
    }

    fun clear() {
        while (poll() != null) { /* discard */ }
    }

    val size: Int get() = (tail.get() - head.get()).toInt().coerceAtLeast(0)
    val isEmpty: Boolean get() = head.get() == tail.get()
}
