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
 * Watch-side TCP receiver.
 *
 * Fixed:
 * - receiveLoop reads ACTUAL payloadSize from wire header (field 10-11)
 * - WireChunk now carries nalSeq + chunkIdx for FrameAssembler
 * - timestampUs from wire header (not System.nanoTime())
 * - dataBuf reused per receive iteration (no per-chunk copy in receiveLoop)
 */
class DirectSocketClient(
    private val discovery: NetworkDiscovery,
    private val port: Int = StreamProtocol.DIRECT_SOCKET_PORT
) {
    private val tag = "DirectSocketClient"
    private var socket: Socket? = null
    private val closed = AtomicBoolean(false)

    data class WireChunk(
        val nalSeq: Int,           // ✅ Global NAL sequence (for FrameAssembler grouping)
        val chunkIdx: Int,         // ✅ Chunk index within this NAL (0..totalChunks-1)
        val totalChunks: Int,
        val timestampUs: Long,     // ✅ From wire header (correct PTS)
        val isKeyframe: Boolean,
        val nalType: Int,
        val data: ByteArray,       // Slice — caller must NOT hold ref after onChunk returns
        val dataSize: Int
    )

    suspend fun connect(
        onStateChange: (Boolean) -> Unit,
        onChunk: ((WireChunk) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        var attempt = 0
        val maxAttempts = 10

        while (!closed.get() && attempt < maxAttempts) {
            val host = discovery.discoveredHost.value
            if (host == null) {
                delay(1000)
                continue
            }
            attempt++
            Log.i(tag, "Connect attempt $attempt/$maxAttempts → $host:$port")
            try {
                val s = Socket().apply {
                    connect(InetSocketAddress(host, port), 5_000)
                    tcpNoDelay = true
                    soTimeout = 15_000
                    setPerformancePreferences(0, 2, 1)  // latency > bandwidth > connection time
                }
                socket = s
                Log.i(tag, "✅ Connected to phone at $host:$port")
                onStateChange(true)
                attempt = 0
                receiveLoop(s.inputStream, onChunk)
                onStateChange(false)
            } catch (e: IOException) {
                Log.w(tag, "Connection failed: ${e.message}")
                delay(minOf(500L * attempt, 8_000L))
            }
        }
        onStateChange(false)
        Log.e(tag, "Max reconnect attempts exceeded")
    }

    private fun receiveLoop(
        stream: InputStream,
        onChunk: ((WireChunk) -> Unit)?
    ) {
        val headerBuf = ByteArray(StreamProtocol.WIRE_HEADER_SIZE)   // 20 bytes
        val dataBuf   = ByteArray(StreamProtocol.CHUNK_MTU)          // Reused every iteration — NO per-chunk alloc

        try {
            while (!closed.get()) {
                // 1. Read fixed-size header
                if (!readExact(stream, headerBuf, StreamProtocol.WIRE_HEADER_SIZE)) return

                // 2. Parse header fields
                val nalSeq      = readInt(headerBuf, StreamProtocol.HDR_NAL_SEQ)
                val chunkIdx    = readShort(headerBuf, StreamProtocol.HDR_CHUNK_IDX).toInt() and 0xFFFF
                val totalChunks = readShort(headerBuf, StreamProtocol.HDR_TOTAL_CHUNKS).toInt() and 0xFFFF
                val isKeyframe  = (headerBuf[StreamProtocol.HDR_FLAGS].toInt() and 0x01) != 0
                val nalType     = headerBuf[StreamProtocol.HDR_NAL_TYPE].toInt() and 0xFF
                val payloadSize = readShort(headerBuf, StreamProtocol.HDR_PAYLOAD_SIZE).toInt() and 0xFFFF  // ✅ ACTUAL size
                val timestampUs = readLong(headerBuf, StreamProtocol.HDR_TIMESTAMP_US)   // ✅ From sender

                // 3. Validate before reading
                if (payloadSize <= 0 || payloadSize > dataBuf.size) {
                    Log.e(tag, "Invalid payloadSize=$payloadSize — protocol error, closing")
                    return
                }

                // 4. Read EXACTLY payloadSize bytes — ✅ FIXED (was reading CHUNK_MTU always)
                if (!readExact(stream, dataBuf, payloadSize)) return

                // 5. Deliver to FrameAssembler — dataBuf is reused so caller MUST copy data if holding
                onChunk?.invoke(
                    WireChunk(
                        nalSeq      = nalSeq,
                        chunkIdx    = chunkIdx,
                        totalChunks = totalChunks,
                        timestampUs = timestampUs,
                        isKeyframe  = isKeyframe,
                        nalType     = nalType,
                        data        = dataBuf,   // Shared buffer — FrameAssembler copies on receipt
                        dataSize    = payloadSize
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
        ((buf[offset  ].toInt() and 0xFF) shl 24) or
        ((buf[offset+1].toInt() and 0xFF) shl 16) or
        ((buf[offset+2].toInt() and 0xFF) shl  8) or
         (buf[offset+3].toInt() and 0xFF)

    private fun readShort(buf: ByteArray, offset: Int): Short =
        (((buf[offset  ].toInt() and 0xFF) shl 8) or
          (buf[offset+1].toInt() and 0xFF)).toShort()

    private fun readLong(buf: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0..7) v = (v shl 8) or (buf[offset + i].toLong() and 0xFF)
        return v
    }

    fun close() {
        closed.set(true)
        runCatching { socket?.close() }
        socket = null
    }
}
