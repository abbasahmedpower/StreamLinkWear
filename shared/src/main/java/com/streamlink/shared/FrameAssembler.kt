package com.streamlink.shared

import android.util.Log

/**
 * Reassembles multi-chunk NAL units from DirectSocketClient chunks.
 *
 * On TCP transport, chunks of the same NAL arrive in-order (TCP guarantees this).
 * So we just accumulate chunks until totalChunks received, then emit.
 *
 * Emits AssembledNal with a start-code prefix (0x00 0x00 0x00 0x01) so
 * DirectStreamPlayer can feed it directly to MediaCodec without modification.
 */
class FrameAssembler {

    data class AssembledNal(
        val nalSeq: Int,
        val data: ByteArray,          // Complete NAL data WITH 4-byte start code prefix
        val size: Int,
        val timestampUs: Long,
        val deadlineUs: Long,
        val isKeyframe: Boolean,
        val nalType: Int
    )

    private class PendingNal(
        val nalSeq: Int,
        val totalChunks: Int,
        val timestampUs: Long,
        val deadlineUs: Long,
        val isKeyframe: Boolean,
        val nalType: Int
    ) {
        // Pre-allocate worst-case: totalChunks * CHUNK_MTU + start code
        val buf = ByteArray(totalChunks * StreamProtocol.CHUNK_MTU + 4)
        var writePos = 4           // Reserve first 4 bytes for start code
        var chunksReceived = 0
        /** Wall-clock insertion time for TTL-based eviction (prevents OOM on 30-min sessions) */
        val insertionMs: Long = System.currentTimeMillis()
    }

    private val pending = LinkedHashMap<Int, PendingNal>(32)
    private val tag = "FrameAssembler"
    private val startCode = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    /**
     * Call for each WireChunk received.
     * Returns AssembledNal when all chunks for a NAL are received, null otherwise.
     * 
     * IMPORTANT: chunk.data is a SHARED buffer — copy immediately.
     */
    fun onChunk(chunk: DirectSocketClient.WireChunk): AssembledNal? {

        // Fast path: single-chunk NAL (most NALs in practice)
        if (chunk.totalChunks == 1) {
            val buf = ByteArray(chunk.dataSize + 4)
            startCode.copyInto(buf)
            System.arraycopy(chunk.data, 0, buf, 4, chunk.dataSize)
            return AssembledNal(
                nalSeq     = chunk.nalSeq,
                data       = buf,
                size       = buf.size,
                timestampUs = chunk.timestampUs,
                deadlineUs  = chunk.deadlineUs,
                isKeyframe  = chunk.isKeyframe,
                nalType     = chunk.nalType
            )
        }

        // Multi-chunk path
        val nal = pending.getOrPut(chunk.nalSeq) {
            PendingNal(chunk.nalSeq, chunk.totalChunks, chunk.timestampUs, chunk.deadlineUs, chunk.isKeyframe, chunk.nalType)
        }

        // ✅ FIX: Bounds checking to prevent buffer overflow (ArrayIndexOutOfBoundsException)
        if (nal.writePos + chunk.dataSize > nal.buf.size) {
            Log.e(tag, "NAL buffer overflow: seq=${chunk.nalSeq}, dropping")
            pending.remove(chunk.nalSeq)
            return null
        }

        // Append chunk data immediately (chunk.data is shared/reused)
        System.arraycopy(chunk.data, 0, nal.buf, nal.writePos, chunk.dataSize)
        nal.writePos += chunk.dataSize
        nal.chunksReceived++

        if (nal.chunksReceived == nal.totalChunks) {
            pending.remove(chunk.nalSeq)

            // Write start code at the front
            startCode.copyInto(nal.buf)

            return AssembledNal(
                nalSeq      = nal.nalSeq,
                data        = nal.buf,
                size        = nal.writePos,
                timestampUs = nal.timestampUs,
                deadlineUs  = nal.deadlineUs,
                isKeyframe  = nal.isKeyframe,
                nalType     = nal.nalType
            )
        }

        // ── Stale frame eviction (prevents OOM over long sessions) ────────────────
        // Primary: deadline-based eviction (nanosecond accuracy)
        // Secondary: wall-clock TTL (500ms) for entries without a valid deadline
        //            — this is the critical fix for OOM after 30 minutes.
        val nowNs = System.nanoTime()
        val nowMs = System.currentTimeMillis()
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val v = entry.value
            val deadlineExpired = v.deadlineUs > 0 && nowNs / 1000 > v.deadlineUs
            val ageExpired      = (nowMs - v.insertionMs) > 500L
            if (deadlineExpired || ageExpired) {
                Log.w(tag, "Evicting stale NAL seq=${entry.key} (deadline=$deadlineExpired age=$ageExpired)")
                it.remove()
            }
        }

        if (pending.size > 16) {
            val stalest = pending.entries.iterator().next()
            Log.w(tag, "Evicting oldest NAL seq=${stalest.key} (queue full)")
            pending.remove(stalest.key)
        }

        return null
    }

    fun reset() = pending.clear()
}
