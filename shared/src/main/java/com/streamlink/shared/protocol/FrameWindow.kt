package com.streamlink.shared.protocol

import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Phase 2 — FrameWindow: Zero-allocation sliding window
 *
 * يحل محل ConcurrentHashMap<Long, PendingFrame> في الـ hot path.
 *
 * المشاكل اللي كانت في ConcurrentHashMap:
 * 1. كل Long key → boxing (heap allocation) لكل frame
 * 2. Hashing + potential resize عند كل عملية
 * 3. GC pressure على مسار بيتنفذ آلاف المرات/ثانية
 *
 * الحل هنا:
 * - Ring buffer ثابت الحجم (capacity لازم قوة 2)
 * - Slot index = sequence & mask → direct array access، مش hashing
 * - كل العمليات على AtomicLongArray / AtomicIntegerArray → thread-safe، zero allocation
 *
 * ⚠️ شرط أساسي: عدد الفريمات unacked في أي لحظة لازم ≤ capacity.
 * لو تجاوز ده، هيحصل slot collision بين sequence قديم وجديد.
 * BackpressureEngine (يراجع BackpressureEngine.kt) هو المسؤول عن منع هذا.
 *
 * البنية المحسوبة لـ capacity = 1024:
 * - sequences:  1024 * 8B = 8KB
 * - sentAtMs:   1024 * 8B = 8KB
 * - acked:      1024 * 4B = 4KB
 * - dupAckCount:1024 * 4B = 4KB
 * Total: 24KB — يناسب L1/L2 cache تماماً.
 *
 * ملاحظة على الـ capacity (1024):
 * Bandwidth-Delay Product بين الساعة والموبايل على WiFi Direct:
 *   BDP = bandwidth × RTT = 2Mbps × 60ms ≈ 120Kb ≈ 15KB
 * كل chunk ≈ 3.9KB → في-الطريق في نفس الوقت ≈ 15KB / 3.9KB ≈ 4 chunks فقط!
 * 1024 يبقى كبير جداً للحالة الحالية، لكن لو الشبكة اتطورت (WiFi6/5G)
 * ممكن يوصل لـ 50-100 chunks in-flight. 1024 آمن ومساحته صغيرة.
 */
class FrameWindow(val capacity: Int = 1024) {

    init {
        require(capacity > 0 && capacity and (capacity - 1) == 0) {
            "FrameWindow capacity must be a power of 2, got: $capacity"
        }
    }

    private val mask = (capacity - 1).toLong()

    // sequence number stored per slot (-1L = empty)
    private val sequences   = AtomicLongArray(capacity).also { a -> for (i in 0 until capacity) a.set(i, -1L) }
    // send timestamp (System.currentTimeMillis())
    private val sentAtMs    = AtomicLongArray(capacity)
    // 0 = pending, 1 = acked
    private val acked       = AtomicIntegerArray(capacity)
    // duplicate ACK counter for fast retransmit detection
    private val dupAckCount = AtomicIntegerArray(capacity)

    // ── Stats counters (updated atomically, no padding needed — read/write on different threads but infrequently)
    @Volatile private var _registered = 0L
    @Volatile private var _acked      = 0L
    @Volatile private var _retransmits = 0L

    private fun slot(seq: UInt): Int = (seq.toLong() and mask).toInt()

    // ── Write side (producer / sender) ────────────────────────────────────────

    /**
     * Registers a newly-sent sequence number into the window.
     * Must be called BEFORE the frame is handed to the network layer.
     */
    fun register(seq: UInt, nowMs: Long = System.currentTimeMillis()) {
        val i = slot(seq)
        sequences[i]   = seq.toLong()
        sentAtMs[i]    = nowMs
        acked[i]       = 0
        dupAckCount[i] = 0
        _registered++
    }

    // ── Read side (ACK receiver) ───────────────────────────────────────────────

    /** Marks [seq] as acknowledged. Safe to call from any thread. */
    fun onAck(seq: UInt) {
        val i = slot(seq)
        if (sequences[i] == seq.toLong()) {
            acked[i] = 1
            _acked++
        }
    }

    /** Returns true if [seq] is registered AND has been acknowledged. */
    fun isAcked(seq: UInt): Boolean {
        val i = slot(seq)
        return sequences[i] == seq.toLong() && acked[i] == 1
    }

    /**
     * Increments the duplicate-ACK counter for [seq].
     * Returns true exactly when the count reaches [threshold] — this is the
     * trigger point for fast retransmit. Calling again after threshold returns false,
     * preventing multiple retransmit triggers for the same loss event.
     */
    fun onDuplicateAck(seq: UInt, threshold: Int = 3): Boolean {
        val i = slot(seq)
        if (sequences[i] != seq.toLong()) return false
        return dupAckCount.incrementAndGet(i) == threshold
    }

    /**
     * Returns the timestamp (ms) when [seq] was registered, or -1 if not found.
     * Used for RTT calculation: RTT = currentTimeMs - sentAt(ackedSeq)
     */
    fun sentAt(seq: UInt): Long {
        val i = slot(seq)
        return if (sequences[i] == seq.toLong()) sentAtMs[i] else -1L
    }

    /**
     * Calculates RTT for a newly ACKed sequence.
     * Returns -1L if the sequence was not found in the window.
     */
    fun rttMs(seq: UInt, nowMs: Long = System.currentTimeMillis()): Long {
        val sent = sentAt(seq)
        return if (sent < 0L) -1L else (nowMs - sent).coerceAtLeast(0L)
    }

    /**
     * Evicts a slot explicitly (e.g., after retransmit gives up on a sequence).
     * After eviction, isAcked/onDuplicateAck for this seq return false/-1.
     */
    fun evict(seq: UInt) {
        val i = slot(seq)
        if (sequences[i] == seq.toLong()) sequences[i] = -1L
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    val registeredCount: Long get() = _registered
    val ackedCount: Long      get() = _acked
    val retransmitCount: Long get() = _retransmits

    /** Scan the window and count how many slots are pending (not yet acked). */
    val pendingCount: Int get() {
        var count = 0
        for (i in 0 until capacity) {
            if (sequences[i] >= 0L && acked[i] == 0) count++
        }
        return count
    }

    /** Reset the window — call only when the stream restarts from sequence 0. */
    fun reset() {
        for (i in 0 until capacity) {
            sequences[i]   = -1L
            sentAtMs[i]    = 0L
            acked[i]       = 0
            dupAckCount[i] = 0
        }
        _registered = 0L
        _acked = 0L
        _retransmits = 0L
    }
}
