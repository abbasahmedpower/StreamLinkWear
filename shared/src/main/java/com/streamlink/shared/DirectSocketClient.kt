package com.streamlink.shared

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import com.streamlink.shared.util.LockFreeSpscQueue

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
    private var encryptedChannel: EncryptedChannel? = null

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
                
                // ECDH Handshake
                val kp = KeyExchange.generateEphemeralKeyPair()
                val pubBytes = kp.publicKeyBase64.toByteArray(Charsets.UTF_8)
                val dos = java.io.DataOutputStream(s.outputStream)
                val dis = java.io.DataInputStream(s.inputStream)
                
                dos.writeInt(pubBytes.size)
                dos.write(pubBytes)
                dos.flush()
                
                val theirLen = dis.readInt()
                val theirBytes = ByteArray(theirLen)
                dis.readFully(theirBytes)
                val theirPub = String(theirBytes, Charsets.UTF_8)
                
                val sessionKey = KeyExchange.deriveSessionKey(kp.privateKey, theirPub)
                encryptedChannel = EncryptedChannel(sessionKey)
                
                Log.i(tag, "✅ Connected and Handshake complete with $host:$port")
                onStateChange(true)
                attempt = 0
                
                // Launch touch sender as a sibling coroutine alongside the receive loop
                startTouchSenderOnce()
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
        val headerBuf = ByteArray(StreamProtocol.WIRE_HEADER_SIZE)
        val dataBuf   = ByteArray(StreamProtocol.CHUNK_MTU)

        try {
            while (!closed.get()) {
                // 1. Read fixed-size header
                if (!readExact(stream, headerBuf, StreamProtocol.WIRE_HEADER_SIZE)) return

                val buffer = java.nio.ByteBuffer.wrap(headerBuf).order(java.nio.ByteOrder.BIG_ENDIAN)

                val magic = buffer.getInt()
                if (magic != StreamProtocol.MAGIC_NUMBER) {
                    Log.e(tag, "Invalid MAGIC_NUMBER. Expected ${StreamProtocol.MAGIC_NUMBER}, got $magic")
                    return
                }
                
                val version = buffer.get()
                if (version != StreamProtocol.PROTOCOL_VERSION) {
                    Log.e(tag, "Unsupported Protocol Version: $version")
                    return
                }

                // 2. Parse header fields
                val nalSeq      = buffer.getInt()
                val chunkIdx    = buffer.getShort().toInt() and 0xFFFF
                val totalChunks = buffer.getShort().toInt() and 0xFFFF
                val flags       = buffer.get().toInt()
                val isKeyframe  = (flags and 0x01) != 0
                val nalType     = buffer.get().toInt() and 0xFF
                val payloadSize = buffer.getShort().toInt() and 0xFFFF
                val timestampUs = buffer.getLong()

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

    private class TouchFrameTask {
        val wire = ByteArray(StreamProtocol.INPUT_FRAME_SIZE)
        var hasData = false
    }

    // Capacity must be power of 2
    private val touchSendQueue = LockFreeSpscQueue<TouchFrameTask>(64)
    private val touchFreeTasks = LockFreeSpscQueue<TouchFrameTask>(64).apply {
        repeat(64) { offer(TouchFrameTask()) }
    }
    private val touchSenderStarted = AtomicBoolean(false)

    private fun startTouchSenderOnce() {
        if (!touchSenderStarted.compareAndSet(false, true)) return
        Thread({
            while (!closed.get()) {
                val task = touchSendQueue.poll()
                if (task == null) {
                    LockSupport.parkNanos(100_000)
                    continue
                }
                val s = socket
                if (task.hasData && s != null && !s.isClosed) {
                    try {
                        val ec = encryptedChannel
                        if (ec != null) {
                            val encrypted = ec.encrypt(task.wire)
                            // We need to send size since it's variable (GCM tag overhead)
                            val dos = java.io.DataOutputStream(s.outputStream)
                            dos.writeInt(encrypted.size)
                            dos.write(encrypted)
                        } else {
                            s.outputStream.write(task.wire)
                        }
                        // TCP_NODELAY is enabled, so no flush needed
                    } catch (e: IOException) {
                        Log.w(tag, "Touch send failed: ${e.message}")
                    }
                }
                task.hasData = false
                touchFreeTasks.offer(task)
            }
        }, "SL-TouchSender").apply { 
            priority = Thread.MAX_PRIORITY - 1
            isDaemon = true
            start() 
        }
    }

    /** Call this to queue a touch packet for reverse-channel delivery to phone. */
    fun sendTouch(
        phase: TouchPhase,
        pointerId: Int,
        nx: Float,
        ny: Float,
        seq: Int,
        timestampUs: Long
    ) {
        val task = touchFreeTasks.poll() ?: return // Dropped if pool is exhausted (extreme load)
        TouchCodec.encodeDirect(phase, pointerId, nx, ny, seq, timestampUs, task.wire)
        task.hasData = true
        if (!touchSendQueue.offer(task)) {
            task.hasData = false
            touchFreeTasks.offer(task)
        }
    }

    /** Call this to queue a control packet for reverse-channel delivery to phone. */
    fun sendControl(command: Int, value: Int) {
        val task = touchFreeTasks.poll() ?: return // Dropped if pool is exhausted
        ControlCodec.encodeDirect(command, value, task.wire)
        task.hasData = true
        if (!touchSendQueue.offer(task)) {
            task.hasData = false
            touchFreeTasks.offer(task)
        }
    }

    /** Returns true when TCP socket is open and handshake completed. */
    fun isConnected(): Boolean {
        val s = socket
        return s != null && s.isConnected && !s.isClosed && encryptedChannel != null
    }

    fun close() {
        closed.set(true)
        runCatching { socket?.close() }
        socket = null
    }
}
