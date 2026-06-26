package com.streamlink.shared

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray

class CacheAlignedFramePacket {
    @Suppress("unused") private var _p1 = 0L; @Suppress("unused") private var _p2 = 0L
    @Suppress("unused") private var _p3 = 0L; @Suppress("unused") private var _p4 = 0L
    @Suppress("unused") private var _p5 = 0L; @Suppress("unused") private var _p6 = 0L
    @Suppress("unused") private var _p7 = 0L

    @Volatile var id: Long = 0L
    val data: ByteArray = ByteArray(StreamProtocol.CHUNK_MTU + 32)
    @Volatile var size: Int = 0
    @Volatile var timestampUs: Long = 0L
    @Volatile var isKeyframe: Boolean = false
    @Volatile var nalType: Int = 0

    @Suppress("unused") private var _p8 = 0L;  @Suppress("unused") private var _p9 = 0L
    @Suppress("unused") private var _p10 = 0L; @Suppress("unused") private var _p11 = 0L
    @Suppress("unused") private var _p12 = 0L; @Suppress("unused") private var _p13 = 0L
    @Suppress("unused") private var _p14 = 0L

    fun resetForReuse() {
        id = 0L
        size = 0
        timestampUs = 0L
        isKeyframe = false
        nalType = 0
    }
}

class LockFreeFramePool(private val capacity: Int = StreamProtocol.FRAME_POOL_CAPACITY) {
    init { require(capacity and (capacity - 1) == 0) { "Capacity must be power of 2" } }

    private val mask = (capacity - 1).toLong()
    private val buffer = AtomicReferenceArray<CacheAlignedFramePacket>(capacity)

    @Suppress("unused") private var _hPad1 = 0L; @Suppress("unused") private var _hPad2 = 0L
    @Suppress("unused") private var _hPad3 = 0L; @Suppress("unused") private var _hPad4 = 0L
    private val head = AtomicLong(0L)
    @Suppress("unused") private var _hPad5 = 0L; @Suppress("unused") private var _hPad6 = 0L

    @Suppress("unused") private var _tPad1 = 0L; @Suppress("unused") private var _tPad2 = 0L
    @Suppress("unused") private var _tPad3 = 0L; @Suppress("unused") private var _tPad4 = 0L
    private val tail = AtomicLong(0L)
    @Suppress("unused") private var _tPad5 = 0L; @Suppress("unused") private var _tPad6 = 0L

    init { for (i in 0 until capacity) buffer.set(i, CacheAlignedFramePacket()) }

    fun acquire(): CacheAlignedFramePacket? {
        while (true) {
            val h = head.get()
            val t = tail.get()
            if (h == t) return null
            val idx = (h and mask).toInt()
            val packet = buffer.get(idx) ?: continue
            if (head.compareAndSet(h, h + 1)) return packet
        }
    }

    fun release(packet: CacheAlignedFramePacket) {
        packet.resetForReuse()
        while (true) {
            val t = tail.get()
            val idx = (t and mask).toInt()
            if (tail.compareAndSet(t, t + 1)) {
                buffer.set(idx, packet)
                return
            }
        }
    }

    val availableCount: Int get() = (tail.get() - head.get()).toInt().coerceIn(0, capacity)
}
