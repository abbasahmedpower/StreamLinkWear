package com.streamlink.shared

import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
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

    // Global NAL sequence number — unique per NAL emitted (wraps at Int.MAX_VALUE, handled by receiver)
    private val globalNalSeq = AtomicInteger(0)

    private val nalPool = ArrayBlockingQueue<ByteArray>(StreamProtocol.NAL_POOL_CAPACITY)

    /**
     * Pipeline mode — zero List allocation.
     * onChunkReady(wire, wireSize, payloadSize)
     */
    fun chunkFramePipeline(
        hardened: HardenedFrame,
        onChunkReady: (wire: ByteArray, wireSize: Int, payloadSize: Int) -> Unit
    ) {
        val buf = hardened.buffer
        val limit = hardened.size
        if (limit < 4) return

        val data = if (buf.hasArray()) buf.array() else {
            val tmp = acquireNalBuffer(limit)
            buf.get(tmp, 0, limit)
            buf.rewind()
            tmp
        }

        var nalStart = -1
        var i = 0

        while (i <= limit - 4) {
            val word = readInt(data, i)
            val isStartCode4 = (word == START_CODE_3)
            val isStartCode3 = !isStartCode4 && (word and START_CODE_MASK) == START_CODE_3B

            if (isStartCode4 || isStartCode3) {
                if (nalStart != -1) {
                    emitNal(data, nalStart, i - nalStart, hardened.timestampUs, hardened.isKeyframe, onChunkReady)
                }
                nalStart = i + (if (isStartCode4) 4 else 3)
                i += if (isStartCode4) 4 else 3
            } else {
                i++
            }
        }

        if (nalStart != -1 && nalStart < limit) {
            emitNal(data, nalStart, limit - nalStart, hardened.timestampUs, hardened.isKeyframe, onChunkReady)
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

    private fun emitNal(
        data: ByteArray, offset: Int, size: Int,
        timestampUs: Long, isKeyframe: Boolean,
        onChunkReady: (ByteArray, Int, Int) -> Unit
    ) {
        val nalType = data[offset].toInt() and 0x1F
        val totalChunks = (size + StreamProtocol.CHUNK_MTU - 1) / StreamProtocol.CHUNK_MTU
        val nalSeq = globalNalSeq.getAndIncrement()  // ✅ Global unique ID per NAL

        var chunkOffset = offset
        var remaining = size
        var chunkIdx = 0

        while (remaining > 0) {
            val chunkPayload = minOf(remaining, StreamProtocol.CHUNK_MTU)
            val wire = WireBufferPool.acquire()
            val wireSize = encodeWireFrame(
                wire, data, chunkOffset, chunkPayload,
                nalSeq, chunkIdx, totalChunks,
                timestampUs, isKeyframe, nalType
            )
            onChunkReady(wire, wireSize, chunkPayload)
            chunkOffset += chunkPayload
            remaining -= chunkPayload
            chunkIdx++
        }
    }

    private fun encodeWireFrame(
        wire: ByteArray, src: ByteArray, srcOffset: Int, payloadSize: Int,
        nalSeq: Int, chunkIdx: Int, totalChunks: Int,
        timestampUs: Long, isKey: Boolean, nalType: Int
    ): Int {
        val buffer = java.nio.ByteBuffer.wrap(wire).order(java.nio.ByteOrder.BIG_ENDIAN)
        
        // HORU Magic Number & Version
        buffer.putInt(StreamProtocol.MAGIC_NUMBER)
        buffer.put(StreamProtocol.PROTOCOL_VERSION)
        
        // nalSeq (4)
        buffer.putInt(nalSeq)
        
        // chunkIdx (2)
        buffer.putShort(chunkIdx.toShort())
        
        // totalChunks (2)
        buffer.putShort(totalChunks.toShort())
        
        // flags (1) — bit0 = isKeyframe
        buffer.put(if (isKey) 0x01.toByte() else 0x00.toByte())
        
        // nalType (1)
        buffer.put(nalType.toByte())
        
        // payloadSize (2)
        buffer.putShort(payloadSize.toShort())
        
        // timestampUs (8)
        buffer.putLong(timestampUs)
        
        // payload
        System.arraycopy(src, srcOffset, wire, StreamProtocol.WIRE_HEADER_SIZE, payloadSize)
        
        return StreamProtocol.WIRE_HEADER_SIZE + payloadSize
    }

    private fun readInt(data: ByteArray, i: Int): Int =
        ((data[i].toInt() and 0xFF) shl 24) or
        ((data[i+1].toInt() and 0xFF) shl 16) or
        ((data[i+2].toInt() and 0xFF) shl  8) or
         (data[i+3].toInt() and 0xFF)

    fun acquireNalBuffer(size: Int): ByteArray = nalPool.poll()?.takeIf { it.size >= size } ?: ByteArray(size)
    fun releaseNalBuffer(buf: ByteArray) { nalPool.offer(buf) }
}

/** Hardened frame wrapper (output of HardenedFrameProcessor). */
data class HardenedFrame(
    val buffer: ByteBuffer,
    val size: Int,
    val timestampUs: Long,
    val isKeyframe: Boolean,
    val sps: ByteArray? = null,
    val pps: ByteArray? = null
)
