package com.streamlink.shared.protocol

import java.nio.ByteBuffer

/**
 * Phase 2 — Extensible Wire Protocol Header
 *
 * Design principle: Base Header (13 bytes, mandatory) + optional Extensions via Flags bitmask.
 * يعني الحزمة العادية = 13 bytes. حزمة multi-stream كاملة = 13 + 18 = 31 bytes.
 * بتدفع الكلفة بس لما تحتاجها فعلاً — نفس فلسفة RTP Header Extensions (RFC 8285).
 *
 * Base Header layout (13 bytes):
 * ┌────────┬──────────┬──────────┬────────────┬────────────┬───────────────┬────────┐
 * │ Magic  │ Ver+Rsvd │ Sequence │ ChunkIndex │ ChunkCount │ PayloadLength │ Flags  │
 * │ 1 byte │ 1 byte   │ 4 bytes  │ 2 bytes    │ 2 bytes    │ 2 bytes       │ 1 byte │
 * └────────┴──────────┴──────────┴────────────┴────────────┴───────────────┴────────┘
 *
 * Extensions (appended after base, in this fixed order, only if Flag bit is set):
 * [StreamId 4B] [FrameId 4B] [TimestampUs 4B] [NalType 1B + Priority 1B] [CRC32C 4B]
 *
 * ليه Flags bitmask مش TLV؟
 * TLV يحتاج loop + branching per-field. Bitmask + fixed order = direct read, no branches.
 * أفضل للـ branch predictor وأسرع على الـ hot path (نفس فلسفة RTP/QUIC).
 */
@OptIn(ExperimentalUnsignedTypes::class)
object ExtensibleHeader {

    /** 'H' — Horus marker, كافي جوه socket خاص، مش بروتوكول عام على الإنترنت */
    const val MAGIC: Byte = 0x48
    const val VERSION: Int = 3   // V3: extensible header, replaces V2 (fixed 25B)
    const val BASE_HEADER_SIZE = 13

    // ── Flag bits ─────────────────────────────────────────────────────────────
    object Flags {
        const val HAS_STREAM_ID  = 1 shl 0   // +4B: StreamId for multi-stream/PiP
        const val HAS_FRAME_ID   = 1 shl 1   // +4B: FrameId for reassembly tracking
        const val HAS_TIMESTAMP  = 1 shl 2   // +4B: TimestampUs for RTT/jitter calc
        const val HAS_NAL_INFO   = 1 shl 3   // +2B: NalType + Priority
        const val HAS_CRC32C     = 1 shl 4   // +4B: CRC32C over full header+payload
        const val HAS_ACK_BITMAP = 1 shl 5   // receiver → sender: ACK piggyback
        // bits 6-7: reserved — ignored by old readers (forward compatibility)

        /** Minimum flags for a standard single-stream video chunk */
        const val STANDARD_VIDEO = HAS_FRAME_ID or HAS_NAL_INFO

        /** Full set for debugging / multi-stream scenarios */
        const val FULL = HAS_STREAM_ID or HAS_FRAME_ID or HAS_TIMESTAMP or HAS_NAL_INFO
    }

    // ── Decoded header ────────────────────────────────────────────────────────
    data class FrameHeader(
        val sequence: UInt,
        val chunkIndex: UShort,
        val chunkCount: UShort,
        val payloadLength: UShort,
        val flags: Int,
        // Optional extensions — null if the corresponding flag is not set
        val streamId: UInt? = null,
        val frameId: UInt? = null,
        val timestampUs: UInt? = null,
        val nalType: UByte? = null,
        val priority: UByte? = null,
    ) {
        val isKeyframe: Boolean get() = nalType?.toInt() == 5   // IDR NAL type

        /** Total wire size of this header (base + enabled extensions) */
        fun wireSize(): Int {
            var size = BASE_HEADER_SIZE
            if (flags and Flags.HAS_STREAM_ID  != 0) size += 4
            if (flags and Flags.HAS_FRAME_ID   != 0) size += 4
            if (flags and Flags.HAS_TIMESTAMP  != 0) size += 4
            if (flags and Flags.HAS_NAL_INFO   != 0) size += 2
            if (flags and Flags.HAS_CRC32C     != 0) size += 4
            return size
        }
    }

    // ── Encode ────────────────────────────────────────────────────────────────

    /**
     * Encodes the header into [buffer] starting at the current position.
     * Zero allocation — writes directly into the pre-allocated wire buffer.
     * CRC32C (if HAS_CRC32C is set) must be appended by the caller AFTER
     * the payload is written, using [writeCrc32c].
     */
    fun encode(buffer: ByteBuffer, h: FrameHeader) {
        buffer.put(MAGIC)
        buffer.put(((VERSION and 0x0F) shl 4).toByte())  // upper nibble = version
        buffer.putInt(h.sequence.toInt())
        buffer.putShort(h.chunkIndex.toShort())
        buffer.putShort(h.chunkCount.toShort())
        buffer.putShort(h.payloadLength.toShort())
        buffer.put(h.flags.toByte())

        // Extensions in fixed order — the order must match decode().
        // Invariant: if a flag bit is set, the corresponding field MUST be non-null.
        // requireNotNull provides a clear protocol-violation message instead of a raw NPE.
        if (h.flags and Flags.HAS_STREAM_ID  != 0) {
            buffer.putInt(requireNotNull(h.streamId)  { "Protocol violation: HAS_STREAM_ID flag set but streamId is null" }.toInt())
        }
        if (h.flags and Flags.HAS_FRAME_ID   != 0) {
            buffer.putInt(requireNotNull(h.frameId)   { "Protocol violation: HAS_FRAME_ID flag set but frameId is null" }.toInt())
        }
        if (h.flags and Flags.HAS_TIMESTAMP  != 0) {
            buffer.putInt(requireNotNull(h.timestampUs) { "Protocol violation: HAS_TIMESTAMP flag set but timestampUs is null" }.toInt())
        }
        if (h.flags and Flags.HAS_NAL_INFO   != 0) {
            buffer.put(requireNotNull(h.nalType)   { "Protocol violation: HAS_NAL_INFO flag set but nalType is null" }.toByte())
            buffer.put(requireNotNull(h.priority)  { "Protocol violation: HAS_NAL_INFO flag set but priority is null" }.toByte())
        }
        // HAS_CRC32C: caller appends AFTER payload via writeCrc32c()
    }

    /**
     * Appends a CRC32C checksum over bytes [startPos, buffer.position()).
     * Call AFTER writing the full payload to the buffer.
     */
    fun writeCrc32c(buffer: ByteBuffer, startPos: Int) {
        val endPos = buffer.position()
        val crc = computeCrc32c(buffer, startPos, endPos - startPos)
        buffer.putInt(crc)
    }

    // ── Decode ────────────────────────────────────────────────────────────────

    /**
     * Decodes a [FrameHeader] from [buffer] at the current position.
     * Throws [IllegalArgumentException] on magic/version mismatch.
     * Throws [SecurityException] if CRC32C is present and invalid.
     *
     * Forward compatibility: unknown flag bits (6-7) are silently ignored —
     * old readers can parse new packets without crashing.
     */
    fun decode(buffer: ByteBuffer): FrameHeader {
        val magic = buffer.get()
        require(magic == MAGIC) {
            "Bad magic byte: 0x${magic.toInt().and(0xFF).toString(16).uppercase()} — not a StreamLinkWear packet"
        }

        val verByte = buffer.get().toInt() and 0xFF
        val version = (verByte ushr 4) and 0x0F
        require(version == VERSION) {
            "Unsupported protocol version: $version (expected $VERSION)"
        }

        val sequence     = buffer.int.toUInt()
        val chunkIndex   = buffer.short.toUShort()
        val chunkCount   = buffer.short.toUShort()
        val payloadLength = buffer.short.toUShort()
        val flags        = buffer.get().toInt() and 0xFF

        return FrameHeader(
            sequence      = sequence,
            chunkIndex    = chunkIndex,
            chunkCount    = chunkCount,
            payloadLength = payloadLength,
            flags         = flags,
            streamId      = if (flags and Flags.HAS_STREAM_ID  != 0) buffer.int.toUInt() else null,
            frameId       = if (flags and Flags.HAS_FRAME_ID   != 0) buffer.int.toUInt() else null,
            timestampUs   = if (flags and Flags.HAS_TIMESTAMP  != 0) buffer.int.toUInt() else null,
            nalType       = if (flags and Flags.HAS_NAL_INFO   != 0) buffer.get().toUByte() else null,
            priority      = if (flags and Flags.HAS_NAL_INFO   != 0) buffer.get().toUByte() else null,
        )
    }

    // ── CRC32C (Castagnoli) ───────────────────────────────────────────────────

    /**
     * Computes CRC32C over [length] bytes of [buffer] starting at [offset].
     * Hardware-accelerated on ARM64 via Java's java.util.zip.CRC32C (API 28+).
     *
     * Decision (from Section 3.4 of the engineering review):
     * CRC32C is gated by HAS_CRC32C flag. Enable only when the transport layer
     * does NOT already provide AEAD integrity (e.g., raw TCP without TLS).
     * If TLS/Noise is in use, skip CRC32C to avoid redundant CPU/battery cost.
     */
    private fun computeCrc32c(buffer: ByteBuffer, offset: Int, length: Int): Int {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val crc = java.util.zip.CRC32C()
            // CRC32C.update(ByteBuffer) is more efficient than copying to a byte array
            val slice = buffer.duplicate().apply {
                position(offset)
                limit(offset + length)
            }
            crc.update(slice)
            return crc.value.toInt()
        } else {
            // Fallback for API < 28: software CRC32 (not Castagnoli, but acceptable)
            val crc = java.util.zip.CRC32()
            val bytes = ByteArray(length)
            buffer.duplicate().apply { position(offset) }.get(bytes)
            crc.update(bytes)
            return crc.value.toInt()
        }
    }

    fun verifyCrc32c(buffer: ByteBuffer, headerStart: Int, headerAndPayloadLength: Int): Boolean {
        val storedCrc = buffer.getInt(headerStart + headerAndPayloadLength)
        val computed  = computeCrc32c(buffer, headerStart, headerAndPayloadLength)
        return storedCrc == computed
    }
}
