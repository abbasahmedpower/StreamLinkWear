package com.streamlink.shared.protocol

/**
 * Phase 2 — ACK Bitmap: Batch acknowledgement (TCP SACK / QUIC ACK Ranges inspired)
 *
 * المشكلة مع ACK-per-frame:
 * لو بنبعت 30fps × 5 chunks/frame = 150 ACKs في الثانية على قناة عرضها 1-2Mbps,
 * الـ ACK traffic وحده يأكل 5-10% من الـ bandwidth.
 *
 * الحل: ACK واحد بيغطي آخر 64 sequence number باستخدام Long bitmask.
 * - bit 0 = largestAcked (أحدث frame)
 * - bit N = largestAcked - N
 * - 1 = acked, 0 = not yet acked (أو ما فيش slot)
 *
 * Wire cost: 4B (largestAcked) + 8B (bitmap) = 12B بدل 150 ACKs × 8B = 1200B
 * = توفير 99% من bandwidth الـ ACK.
 *
 * ملاحظة من المراجعة:
 * bitmap 64-bit مناسب لنافذة صغيرة (max ~4 chunks in-flight للـ BDP الحالي).
 * لو الـ in-flight window كبر لـ مئات الفريمات مستقبلاً → وقتها نحتاج range-encoding
 * زي QUIC (gap + length pairs). دلوقتي مش لازمة.
 */
@OptIn(ExperimentalUnsignedTypes::class)
object AckBitmap {

    /**
     * A compact ACK frame: covers [largestAcked] and the 63 sequences before it.
     * Wire format: UInt(4B) + Long(8B) = 12 bytes total.
     */
    data class AckFrame(
        val largestAcked: UInt,
        val bitmap: Long       // bit i = 1 means (largestAcked - i) is acked
    ) {
        val ackedCount: Int get() = java.lang.Long.bitCount(bitmap)

        /**
         * Returns true if [seq] is covered by this AckFrame and marked as acked.
         * Returns false if [seq] is outside the 64-sequence window.
         */
        fun covers(seq: UInt): Boolean {
            val delta = (largestAcked - seq).toLong()
            if (delta < 0L || delta >= 64L) return false
            return (bitmap ushr delta.toInt()) and 1L == 1L
        }
    }

    /**
     * Builds an AckFrame by scanning [window] for the last 64 sequences up to [largestAcked].
     * Call this on the receiver side to generate a compact ACK to send back.
     *
     * @param window     the FrameWindow tracking in-flight frames
     * @param largestAcked the highest sequence number the receiver has seen
     */
    fun build(window: FrameWindow, largestAcked: UInt): AckFrame {
        var bitmap = 0L
        for (i in 0 until 64) {
            val seq = largestAcked - i.toUInt()
            if (window.isAcked(seq)) {
                bitmap = bitmap or (1L shl i)
            }
        }
        return AckFrame(largestAcked, bitmap)
    }

    /**
     * Applies an AckFrame received from the peer, marking the corresponding
     * sequences as acked in [window] and returning the RTT samples for each.
     *
     * @return list of (sequence, rttMs) pairs for all newly-acked frames
     *         (for feeding into BackpressureEngine or RTT tracker)
     */
    fun apply(window: FrameWindow, frame: AckFrame, nowMs: Long = System.currentTimeMillis()): List<Pair<UInt, Long>> {
        val rttSamples = mutableListOf<Pair<UInt, Long>>()
        for (i in 0 until 64) {
            if ((frame.bitmap ushr i) and 1L == 1L) {
                val seq = frame.largestAcked - i.toUInt()
                if (!window.isAcked(seq)) {
                    val rtt = window.rttMs(seq, nowMs)
                    window.onAck(seq)
                    if (rtt >= 0L) rttSamples.add(Pair(seq, rtt))
                }
            }
        }
        return rttSamples
    }

    /**
     * Encodes an AckFrame into a ByteArray for transport (12 bytes).
     * Used when piggybacking ACKs onto control packets.
     */
    fun encode(frame: AckFrame): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.BIG_ENDIAN)
        buf.putInt(frame.largestAcked.toInt())
        buf.putLong(frame.bitmap)
        return buf.array()
    }

    /** Decodes an AckFrame from a 12-byte ByteArray. */
    fun decode(bytes: ByteArray): AckFrame {
        require(bytes.size >= 12) { "AckFrame requires 12 bytes, got ${bytes.size}" }
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        val largestAcked = buf.int.toUInt()
        val bitmap = buf.long
        return AckFrame(largestAcked, bitmap)
    }

    /**
     * RTT-based loss detection: scans [window] for sequences older than [timeoutMs]
     * that are still pending (not acked). Returns the list of lost sequence numbers.
     *
     * This is the second line of defense beyond 3-dup-ACK detection (see FrameWindow.onDuplicateAck).
     * It catches the last-packet-in-burst loss case where no subsequent packets arrive
     * to trigger duplicate ACKs.
     *
     * @param lastSentSeq the latest sequence number sent (upper bound for scan)
     * @param timeoutMs   RTT × 1.5 is a reasonable starting value (e.g., 90ms for RTT=60ms)
     */
    fun detectRttLoss(
        window: FrameWindow,
        lastSentSeq: UInt,
        timeoutMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): List<UInt> {
        val lost = mutableListOf<UInt>()
        // Scan last 64 sequences (same window as bitmap)
        val start = if (lastSentSeq >= 64u) lastSentSeq - 64u else 0u
        var seq = start
        while (seq <= lastSentSeq) {
            val sent = window.sentAt(seq)
            if (sent >= 0L && !window.isAcked(seq)) {
                val age = nowMs - sent
                if (age > timeoutMs) lost.add(seq)
            }
            seq++
        }
        return lost
    }
}
