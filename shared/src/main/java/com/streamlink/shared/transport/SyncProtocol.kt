package com.streamlink.shared.transport

import java.nio.ByteBuffer

/**
 * Encodes and decodes the payload for the NTP-style synchronization protocol.
 * Packet structure:
 * [0] TYPE (1 byte: 0x01 PING, 0x02 PONG)
 * [1-8] t1 (8 bytes)
 * [9-16] t2 (8 bytes, PONG only)
 * [17-24] t3 (8 bytes, PONG only)
 */
object SyncProtocol {
    const val PACKET_TYPE_PING: Byte = 0x01
    const val PACKET_TYPE_PONG: Byte = 0x02
    
    // Total 5 PING/PONG exchanges during handshake
    const val HANDSHAKE_PINGS = 5

    fun createPingPacket(t1: Long): ByteArray {
        val buffer = ByteBuffer.allocate(9)
        buffer.put(PACKET_TYPE_PING)
        buffer.putLong(t1)
        return buffer.array()
    }

    fun parsePingPacket(payload: ByteArray): Long {
        val buffer = ByteBuffer.wrap(payload)
        buffer.get() // Skip TYPE
        return buffer.long
    }

    fun createPongPacket(t1: Long, t2: Long, t3: Long): ByteArray {
        val buffer = ByteBuffer.allocate(25)
        buffer.put(PACKET_TYPE_PONG)
        buffer.putLong(t1)
        buffer.putLong(t2)
        buffer.putLong(t3)
        return buffer.array()
    }

    class PongData(val t1: Long, val t2: Long, val t3: Long)

    fun parsePongPacket(payload: ByteArray): PongData {
        val buffer = ByteBuffer.wrap(payload)
        buffer.get() // Skip TYPE
        return PongData(
            t1 = buffer.long,
            t2 = buffer.long,
            t3 = buffer.long
        )
    }
}
