package com.streamlink.shared

import java.util.concurrent.ArrayBlockingQueue

/**
 * Bounded pool for wire-send byte arrays.
 * Hard cap prevents heap fragmentation + OOM under sustained load.
 */
object WireBufferPool {
    private const val BUFFER_SIZE = StreamProtocol.CHUNK_MTU + StreamProtocol.WIRE_HEADER_SIZE + 16
    private val pool = ArrayBlockingQueue<ByteArray>(StreamProtocol.WIRE_POOL_CAPACITY)

    fun acquire(): ByteArray = pool.poll() ?: ByteArray(BUFFER_SIZE)

    fun release(buf: ByteArray) {
        if (buf.size >= BUFFER_SIZE) {
            pool.offer(buf)  // offer() drops silently if pool is full — no blocking
        }
    }

    val pooledCount: Int get() = pool.size
}
