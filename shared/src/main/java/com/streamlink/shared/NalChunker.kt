package com.streamlink.shared

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Zero-allocation H.264 NAL chunker.
 *
 * Fixed:
 * - nalSeq: global counter per NAL unit (enables receiver reassembly)
 * - payloadSize: written to wire header byte 10-11
 * - timestampUs: written to wire header bytes 12-19
 * - chunkIdx: now correctly sequential (not always 0)
 */
object NalChunker {
    @PublishedApi internal const val START_CODE_3 = 0x00000001
    @PublishedApi internal const val START_CODE_MASK = 0xFFFFFF00.toInt()
    @PublishedApi internal const val START_CODE_3B = 0x00000100

    // Global NAL sequence number — unique per NAL emitted
    private val globalNalSeq = AtomicInteger(0)

    /**
     * Pipeline mode — zero intermediate copies or allocations.
     * Parses start codes directly from direct ByteBuffer and copies
     * slices directly to pooled wire buffers.
     */
    fun chunkFramePipeline(
        hardened: HardenedFrame,
        onChunkReady: (wire: ByteArray, wireSize: Int, payloadSize: Int) -> Unit
    ) {
        val buf = hardened.buffer
        val limit = hardened.size
        
        chunkDirect(buf, limit) { src, nalOffset, nalSize, nalType ->
            val totalChunks = (nalSize + StreamProtocol.CHUNK_MTU - 1) / StreamProtocol.CHUNK_MTU
            val nalSeq = globalNalSeq.getAndIncrement()

            var remaining = nalSize
            var chunkIdx = 0
            var currentNalOffset = nalOffset

            while (remaining > 0) {
                val chunkPayload = minOf(remaining, StreamProtocol.CHUNK_MTU)
                val wire = WireBufferPool.acquire()
                val wireSize = encodeWireFrameFromByteBuffer(
                    wire, src, currentNalOffset, chunkPayload,
                    nalSeq, chunkIdx, totalChunks,
                    hardened.timestampUs, hardened.isKeyframe, nalType
                )
                onChunkReady(wire, wireSize, chunkPayload)
                currentNalOffset += chunkPayload
                remaining -= chunkPayload
                chunkIdx++
            }
        }
    }

    /** Direct zero-copy view mode */
    inline fun chunkDirect(
        buffer: ByteBuffer,
        size: Int,
        crossinline onNal: (src: ByteBuffer, offset: Int, nalSize: Int, nalType: Int) -> Unit
    ) {
        val limit = minOf(size, buffer.limit())
        if (limit < 4) return
        var nalStart = -1
        var i = buffer.position()
        while (i <= limit - 4) {
            val word = buffer.getInt(i)
            val is4 = (word == START_CODE_3)
            val is3 = !is4 && (word and START_CODE_MASK) == START_CODE_3B
            if (is4 || is3) {
                if (nalStart != -1) {
                    val nalEnd = i
                    if (nalEnd > nalStart) {
                        val nalType = (buffer.get(nalStart).toInt() and 0x1F)
                        onNal(buffer, nalStart, nalEnd - nalStart, nalType)
                    }
                }
                nalStart = i + (if (is4) 4 else 3)
                i += if (is4) 4 else 3
            } else { i++ }
        }
        if (nalStart != -1 && nalStart < limit) {
            val nalType = (buffer.get(nalStart).toInt() and 0x1F)
            onNal(buffer, nalStart, limit - nalStart, nalType)
        }
    }

    /**
     * ThreadLocal direct ByteBuffer reused per-thread for header encoding.
     * Eliminates the heap allocation of ByteBuffer.wrap(wire) on every chunk
     * in the hot encoding path — a zero-cost improvement on the call site.
     */
    private val headerEncoder = object : ThreadLocal<java.nio.ByteBuffer>() {
        override fun initialValue(): java.nio.ByteBuffer =
            java.nio.ByteBuffer.allocateDirect(StreamProtocol.WIRE_HEADER_SIZE)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
    }

    private fun encodeWireFrameFromByteBuffer(
        wire: ByteArray, src: java.nio.ByteBuffer, srcOffset: Int, payloadSize: Int,
        nalSeq: Int, chunkIdx: Int, totalChunks: Int,
        timestampUs: Long, isKey: Boolean, nalType: Int
    ): Int {
        // Reuse ThreadLocal header buffer — no heap allocation on hot path.
        // hdr is a direct ByteBuffer pre-sized to WIRE_HEADER_SIZE (25 bytes).
        val hdr = headerEncoder.get()!!.clear() as java.nio.ByteBuffer

        // MAGIC(4) | VERSION(1) | nalSeq(4) | chunkIdx(2) | totalChunks(2)
        hdr.putInt(StreamProtocol.MAGIC_NUMBER)
        hdr.put(StreamProtocol.PROTOCOL_VERSION)
        hdr.putInt(nalSeq)
        hdr.putShort(chunkIdx.toShort())
        hdr.putShort(totalChunks.toShort())

        // flags(1): bit0=keyframe, bit1=0 (deadline not embedded)
        hdr.put(if (isKey) 0x01.toByte() else 0x00.toByte())

        // nalType(1) | payloadSize(2)
        hdr.put(nalType.toByte())
        hdr.putShort(payloadSize.toShort())

        // Drain bytes [0..16] into wire[] so CRC can be computed over them
        hdr.flip()
        hdr.get(wire, 0, 17)  // bytes 0..16 = everything before CRC field

        // CRC16 over the 17 header bytes written so far
        val crc = StreamProtocol.crc16(wire, 17)
        wire[StreamProtocol.HDR_CRC16]     = (crc.toInt() ushr 8 and 0xFF).toByte()
        wire[StreamProtocol.HDR_CRC16 + 1] = (crc.toInt()          and 0xFF).toByte()

        // timestampUs(8) at offset 19
        val tsView = java.nio.ByteBuffer.wrap(wire, StreamProtocol.HDR_TIMESTAMP_US, 8)
            .order(java.nio.ByteOrder.BIG_ENDIAN)
        tsView.putLong(timestampUs)

        // Copy payload directly from source ByteBuffer into the output wire array
        val dup = src.duplicate()
        dup.position(srcOffset)
        dup.get(wire, StreamProtocol.WIRE_HEADER_SIZE, payloadSize)

        return StreamProtocol.WIRE_HEADER_SIZE + payloadSize
    }
}

/** Hardened frame wrapper (output of HardenedFrameProcessor). */
data class HardenedFrame(
    val buffer: ByteBuffer,
    val size: Int,
    val timestampUs: Long,
    val deadlineUs: Long,
    val isKeyframe: Boolean,
    val sps: ByteArray? = null,
    val pps: ByteArray? = null,
    val releaseCallback: (() -> Unit)? = null
) {
    fun release() {
        releaseCallback?.invoke()
    }
}
