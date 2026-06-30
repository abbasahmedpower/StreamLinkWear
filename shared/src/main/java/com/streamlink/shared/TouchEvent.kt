package com.streamlink.shared

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reverse Input Channel — Watch → Phone.
 * Frame (32 bytes fixed, cache-aligned):
 * Magic(4) | Version(1) | Phase(1) | Seq(4) | PointerId(1) | nx(2) | ny(2) | TimestampUs(8) | Padding(9) = 32
 */
enum class TouchPhase(val wireType: Byte) {
    DOWN(1),
    MOVE(2),
    UP(3),
    CANCEL(4)
}

/**
 * nx, ny are Normalized coordinates (0f to 1f) scaled by 65535 to fit in a UInt16 (Short).
 * This provides ~0.0015% precision, which is more than enough for touch injection.
 */
data class TouchEvent(
    val phase: TouchPhase,
    val pointerId: Int,
    val nx: Float,
    val ny: Float,
    val seq: Int,
    val timestampUs: Long
)

object TouchCodec {

    fun encode(event: TouchEvent, out: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(StreamProtocol.MAGIC_NUMBER_INPUT)
        buf.put(StreamProtocol.PROTOCOL_VERSION)
        buf.put(event.phase.wireType)
        buf.putInt(event.seq)
        buf.put(event.pointerId.toByte())
        
        // Quantize float to UInt16 (0..65535)
        val shortNx = (event.nx.coerceIn(0f, 1f) * 65535f).toInt().toShort()
        val shortNy = (event.ny.coerceIn(0f, 1f) * 65535f).toInt().toShort()
        buf.putShort(shortNx)
        buf.putShort(shortNy)
        
        buf.putLong(event.timestampUs)
        
        // Padding is automatically 0 if array is zero-initialized, 
        // but we just leave the remaining 9 bytes untouched for performance.
        return out
    }

    fun encodeDirect(
        phase: TouchPhase,
        pointerId: Int,
        nx: Float,
        ny: Float,
        seq: Int,
        timestampUs: Long,
        out: ByteArray
    ): ByteArray {
        val buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(StreamProtocol.MAGIC_NUMBER_INPUT)
        buf.put(StreamProtocol.PROTOCOL_VERSION)
        buf.put(phase.wireType)
        buf.putInt(seq)
        buf.put(pointerId.toByte())
        
        val shortNx = (nx.coerceIn(0f, 1f) * 65535f).toInt().toShort()
        val shortNy = (ny.coerceIn(0f, 1f) * 65535f).toInt().toShort()
        buf.putShort(shortNx)
        buf.putShort(shortNy)
        
        buf.putLong(timestampUs)
        return out
    }

    fun decode(frame: ByteArray): TouchEvent? {
        val buf = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        val magic = buf.getInt()
        if (magic != StreamProtocol.MAGIC_NUMBER_INPUT) return null
        
        val version = buf.get()
        if (version != StreamProtocol.PROTOCOL_VERSION) return null

        val type = buf.get()
        val seq = buf.getInt()
        val pointerId = buf.get().toInt() and 0xFF
        
        val shortNx = buf.getShort().toInt() and 0xFFFF
        val shortNy = buf.getShort().toInt() and 0xFFFF
        
        val nx = shortNx / 65535f
        val ny = shortNy / 65535f
        
        val ts = buf.getLong()

        val phase = when (type) {
            1.toByte() -> TouchPhase.DOWN
            2.toByte() -> TouchPhase.MOVE
            3.toByte() -> TouchPhase.UP
            else       -> TouchPhase.CANCEL
        }
        
        return TouchEvent(phase, pointerId, nx, ny, seq, ts)
    }
}
