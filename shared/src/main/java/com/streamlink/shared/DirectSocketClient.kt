package com.streamlink.shared

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DirectSocketClient — Watch-side TCP receiver for H.264 chunks.
 *
 * Connects to phone's DirectSocketServer on port 8999.
 * Reassembles wire chunks, delivers to JitterBuffer.
 */
class DirectSocketClient(
    private val host: String,
    private val port: Int = StreamProtocol.DIRECT_SOCKET_PORT
) {
    private val tag = "DirectSocketClient"
    private var socket: Socket? = null
    private val closed = AtomicBoolean(false)

    data class WireChunk(
        val seq: Int,
        val totalChunks: Int,
        val chunkIndex: Int,
        val timestampUs: Long,
        val isKeyframe: Boolean,
        val nalType: Int,
        val data: ByteArray,
        val dataSize: Int
    )

    /**
     * Connect with retry. Calls [onStateChange] with true/false on connect/disconnect.
     * Calls [onChunk] for each received wire chunk.
     */
    suspend fun connect(
        onStateChange: (Boolean) -> Unit,
        onChunk: ((WireChunk) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        var attempt = 0
        val maxAttempts = 10

        while (!closed.get() && attempt < maxAttempts) {
            attempt++
            Log.i(tag, "Connect attempt $attempt/$maxAttempts → $host:$port")
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5_000)
                s.tcpNoDelay = true
                s.soTimeout = 10_000
                socket = s
                Log.i(tag, "✅ Connected to phone at $host:$port")
                onStateChange(true)
                attempt = 0  // Reset on success
                receiveLoop(s.inputStream, onChunk)
                onStateChange(false)
            } catch (e: IOException) {
                Log.w(tag, "Connection failed: ${e.message}")
                delay(minOf(1_000L * attempt, 8_000L))
            }
        }
        onStateChange(false)
        Log.e(tag, "Max reconnect attempts exceeded")
    }

    private fun receiveLoop(
        stream: InputStream,
        onChunk: ((WireChunk) -> Unit)?
    ) {
        val headerBuf = ByteArray(StreamProtocol.WIRE_HEADER_SIZE)
        val dataBuf = ByteArray(StreamProtocol.CHUNK_MTU + 64)

        try {
            while (!closed.get()) {
                // Read header exactly
                if (!readExact(stream, headerBuf, StreamProtocol.WIRE_HEADER_SIZE)) return

                // Parse header: seq(4) total(2) idx(2) flags(1) nalType(1)
                val seq = readInt(headerBuf, 0)
                val total = readShort(headerBuf, 4).toInt()
                val idx = readShort(headerBuf, 6).toInt()
                val isKeyframe = headerBuf[8].toInt() != 0
                val nalType = headerBuf[9].toInt() and 0xFF

                // Read payload size from seq high bits (simplified — real impl embeds size)
                // For now: read up to CHUNK_MTU
                val payloadSize = minOf(StreamProtocol.CHUNK_MTU, dataBuf.size)
                if (!readExact(stream, dataBuf, payloadSize)) return

                onChunk?.invoke(
                    WireChunk(
                        seq = seq, totalChunks = total, chunkIndex = idx,
                        timestampUs = System.nanoTime() / 1_000,
                        isKeyframe = isKeyframe, nalType = nalType,
                        data = dataBuf.copyOf(payloadSize), dataSize = payloadSize
                    )
                )
            }
        } catch (e: IOException) {
            Log.w(tag, "Receive loop ended: ${e.message}")
        }
    }

    private fun readExact(stream: InputStream, buf: ByteArray, size: Int): Boolean {
        var read = 0
        while (read < size) {
            val n = stream.read(buf, read, size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun readInt(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 24) or
        ((buf[offset+1].toInt() and 0xFF) shl 16) or
        ((buf[offset+2].toInt() and 0xFF) shl 8) or
        (buf[offset+3].toInt() and 0xFF)

    private fun readShort(buf: ByteArray, offset: Int): Short =
        (((buf[offset].toInt() and 0xFF) shl 8) or
        (buf[offset+1].toInt() and 0xFF)).toShort()

    fun close() {
        closed.set(true)
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}
