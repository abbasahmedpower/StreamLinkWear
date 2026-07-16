package com.streamlink.shared.util

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Lock-free Multi-Producer Multi-Consumer (MPMC) bounded queue.
 *
 * ✅ FIX #1 (nano): بديل LockFreeSpscQueue. السبب: iFrameQueue/pFrameQueue/
 * freeTasks في DirectSocketServer بيتكتب عليهم فعليًا من أكتر من Producer
 * (AudioCaptureEngine.captureThread + MirrorDataPlane dispatcher thread)،
 * وكمان iFrameQueue بيتقرا منها من Consumer تاني غير runSender() (مسار
 * الـ eviction جوه sendPooledWire نفسها). الـ SPSC القديمة كانت بتفترض
 * منتج واحد ومستهلك واحد بس وكانت بتستخدم lazySet بدون full memory
 * barrier — تحت الحمل ده بيسبب فقد/تلخبط فريمات صامت.
 *
 * الفرق عن SPSC: offer() بتستخدم compareAndSet على tail (مش lazySet)،
 * وفيه فحص إن الـ slot فاضي قبل ما تُكتب فيه. التكلفة: full memory
 * barrier بدل lazy — أرخص بكتير من فريم بيتلخبط.
 *
 * Capacity لازم تكون power of 2.
 */
class LockFreeMpmcQueue<T : Any>(capacity: Int) {
    init {
        require(capacity > 0 && (capacity and (capacity - 1)) == 0) {
            "Capacity must be a power of 2"
        }
    }

    private val mask = (capacity - 1).toLong()
    private val buffer = AtomicReferenceArray<T>(capacity)
    private val head = AtomicLong(0)
    private val tail = AtomicLong(0)

    /** Thread-safe من أي عدد Producers. */
    fun offer(item: T): Boolean {
        while (true) {
            val t = tail.get()
            val h = head.get()
            if (t - h > mask) return false // الكيو ممتلئة

            val idx = (t and mask).toInt()
            // الـ slot لازم يكون فاضي قبل ما نكتب فيه
            if (buffer.get(idx) != null) return false

            if (tail.compareAndSet(t, t + 1)) {
                buffer.set(idx, item) // set كامل — memory barrier حقيقي
                return true
            }
        }
    }

    /** Thread-safe من أي عدد Consumers. */
    fun poll(): T? {
        while (true) {
            val h = head.get()
            if (h == tail.get()) return null // فاضية

            val idx = (h and mask).toInt()
            val item = buffer.get(idx) ?: return null

            if (head.compareAndSet(h, h + 1)) {
                buffer.set(idx, null)
                return item
            }
        }
    }

    fun clear() {
        while (poll() != null) { /* استمر لحد ما تفضى */ }
    }

    val size: Int get() = (tail.get() - head.get()).toInt().coerceAtLeast(0)
    val isEmpty: Boolean get() = head.get() == tail.get()
}
