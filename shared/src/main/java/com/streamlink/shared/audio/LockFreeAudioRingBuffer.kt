package com.streamlink.shared.audio

import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free SPSC ring buffer مخصص لفريمات PCM16 صوت ثابتة الحجم.
 * زيرو allocation على الـhot path — كل الـslots متعملة pre-allocated من الأول.
 * الهدف: jitter buffer بين thread استقبال الشبكة و thread تشغيل AudioTrack.
 * Capacity لازم تكون power of 2.
 */
class LockFreeAudioRingBuffer(
    capacity: Int,
    private val frameSizeBytes: Int
) {
    init {
        require(capacity > 0 && (capacity and (capacity - 1)) == 0) { "Capacity must be power of 2" }
    }

    private val mask = (capacity - 1).toLong()
    private val slots: Array<ByteArray> = Array(capacity) { ByteArray(frameSizeBytes) }
    private val slotLen = IntArray(capacity)
    private val slotTimestampUs = LongArray(capacity)

    private val head = AtomicLong(0) // بيقرأه/يعدله الـconsumer فقط
    private val tail = AtomicLong(0) // بيقرأه/يعدله الـproducer فقط

    /** Producer: نسخ مباشر جوه slot جاهز، بدون أي allocation. */
    fun write(src: ByteArray, len: Int, timestampUs: Long = 0L): Boolean {
        val t = tail.get()
        val h = head.get()
        if (t - h > mask) return false // البافر مليان — اسقط الفريم عشان الـlatency ما يتراكمش

        val idx = (t and mask).toInt()
        System.arraycopy(src, 0, slots[idx], 0, minOf(len, frameSizeBytes))
        slotLen[idx] = len
        slotTimestampUs[idx] = timestampUs
        tail.lazySet(t + 1)
        return true
    }

    /** Consumer: بينسخ في out (المستدعي بيوفر buffer بحجم frameSizeBytes). يرجع -1 لو فاضي. */
    fun read(out: ByteArray): Int {
        val h = head.get()
        val t = tail.get()
        if (h == t) return -1

        val idx = (h and mask).toInt()
        val len = slotLen[idx]
        System.arraycopy(slots[idx], 0, out, 0, len)
        head.lazySet(h + 1)
        return len
    }

    fun availableFrames(): Int = (tail.get() - head.get()).toInt().coerceAtLeast(0)

    fun clear() { head.set(tail.get()) }
}
