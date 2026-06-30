package com.streamlink.shared

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles encoding/decoding of control messages (e.g. AI Bitrate Adaptation)
 * in a zero-allocation way over the reverse channel.
 */
object ControlCodec {
    
    data class ControlMessage(
        val command: Int,
        val value: Int
    )

    fun encodeDirect(
        command: Int,
        value: Int,
        out: ByteArray
    ): ByteArray {
        val buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(StreamProtocol.MAGIC_NUMBER_CONTROL)
        buf.put(StreamProtocol.PROTOCOL_VERSION)
        buf.put(command.toByte())
        buf.putInt(value)
        return out
    }

    fun decode(frame: ByteArray): ControlMessage? {
        val buf = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        val magic = buf.getInt()
        if (magic != StreamProtocol.MAGIC_NUMBER_CONTROL) return null
        
        val version = buf.get()
        if (version != StreamProtocol.PROTOCOL_VERSION) return null
        
        val command = buf.get().toInt()
        val value = buf.getInt()
        
        return ControlMessage(command, value)
    }
}
