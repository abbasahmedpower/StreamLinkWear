package com.streamlink.shared

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import com.streamlink.shared.util.LockFreeSpscQueue

/**
 * Non-blocking TCP server for H.264 chunk delivery.
 * Uses a dedicated sender thread (LockFreeSpscQueue) to avoid
 * blocking the encoder on TCP write.
 *
 * Handshake protocol (per accepted client):
 *   ← Watch: 4-byte keyLen | keyBytes (ECDH-P256 pub, Base64)
 *   ← Watch: 4-byte watchWidth | 4-byte watchHeight  (real display pixels)
 *   → Phone: 4-byte keyLen | keyBytes (ECDH-P256 pub, Base64)
 *   Both sides derive session key via ECDH + HKDF-SHA256 + pairingCode
 */
class DirectSocketServer {
    private val tag = "DirectSocket"

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private val running = AtomicBoolean(false)
    /** True once the server socket is bound and listening. Used by orchestrator startup sequencing. */
    val isRunning: Boolean get() = running.get()

    class SendTask {
        var wire: ByteArray? = null
        var size: Int = 0
        var isKeyframe: Boolean = false
    }

    private val iFrameQueue = LockFreeSpscQueue<SendTask>(64)
    private val pFrameQueue = LockFreeSpscQueue<SendTask>(256)
    private val freeTasks = LockFreeSpscQueue<SendTask>(512).apply {
        repeat(512) { offer(SendTask()) }
    }
    private val bytesSent = AtomicLong(0L)

    // Queue Metrics
    val queueDepth: Int get() = iFrameQueue.size + pFrameQueue.size
    @Volatile var droppedFrames: Long = 0L
        private set
    @Volatile var averageDelayMs: Float = 0f
        private set

    @Volatile var isClientConnected = false
    var onChunkDelivered: (() -> Unit)? = null
    var onTouchEvent: ((TouchEvent) -> Unit)? = null
    var onControlMessage: ((ControlCodec.ControlMessage) -> Unit)? = null

    /**
     * Called once per accepted client with the watch's real screen dimensions (px).
     * Used by RemoteControlAccessibilityService to map coordinates accurately.
     */
    var onWatchDimensions: ((widthPx: Int, heightPx: Int) -> Unit)? = null

    /**
     * Pairing code injected by the Phone UI. Must match what the Watch sends
     * in its own HKDF derivation, otherwise the session key will differ and
     * decryption will silently fail — i.e., Fail Closed by design.
     */
    @Volatile var pairingCode: String = "000000"

    private var encryptedChannel: EncryptedChannel? = null

    /** Launched alongside the sender thread when a client connects. */
    private fun runInputReceiver(socket: java.net.Socket) {
        val dis = java.io.DataInputStream(socket.inputStream)
        try {
            while (running.get() && !socket.isClosed) {
                // Read 4-byte length prefix
                val len = dis.readInt()
                if (len <= 0 || len > 1024) break // basic sanity check

                val encryptedBuf = ByteArray(len)
                dis.readFully(encryptedBuf)

                val ec = encryptedChannel
                val decrypted = if (ec != null) ec.decrypt(encryptedBuf) else encryptedBuf
                if (decrypted == null || decrypted.size < StreamProtocol.INPUT_FRAME_SIZE) continue

                // Inspect magic number quickly
                val buf = java.nio.ByteBuffer.wrap(decrypted).order(java.nio.ByteOrder.BIG_ENDIAN)
                val magic = buf.getInt()

                if (magic == StreamProtocol.MAGIC_NUMBER_INPUT) {
                    val event = TouchCodec.decode(decrypted)
                    if (event != null) {
                        onTouchEvent?.invoke(event)
                    }
                } else if (magic == StreamProtocol.MAGIC_NUMBER_CONTROL) {
                    val msg = ControlCodec.decode(decrypted)
                    if (msg != null) {
                        onControlMessage?.invoke(msg)
                    }
                }
                // If magic doesn't match, it might be noise — skip silently
            }
        } catch (e: java.io.IOException) {
            android.util.Log.w(tag, "Input receive loop ended: ${e.message}")
        }
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        if (running.getAndSet(true)) return@withContext
        try {
            serverSocket = ServerSocket(StreamProtocol.DIRECT_SOCKET_PORT).apply {
                reuseAddress = true
                soTimeout = 0
            }
            Log.i(tag, "Listening on :${StreamProtocol.DIRECT_SOCKET_PORT}")

            // Sender thread — drains queue into TCP output stream
            val senderThread = Thread({ runSender() }, "SL-TCPSender").apply {
                priority = Thread.MAX_PRIORITY - 1
                isDaemon = true
                start()
            }

            // Accept loop
            while (running.get()) {
                val newClient = try {
                    serverSocket?.accept() ?: break
                } catch (e: IOException) {
                    if (running.get()) Log.w(tag, "Accept error: ${e.message}")
                    break
                }

                Log.i(tag, "Watch connected: ${newClient.inetAddress.hostAddress}")
                closeClient()  // Close old client first
                newClient.tcpNoDelay = true
                newClient.setPerformancePreferences(0, 1, 2)
                clientSocket = newClient

                // ── ECDH Handshake ────────────────────────────────────────
                val dis = java.io.DataInputStream(newClient.inputStream)
                val dos = java.io.DataOutputStream(newClient.outputStream)

                // 1. Read Watch's public key
                val clientLen = dis.readInt()
                if (clientLen <= 0 || clientLen > 8192) throw java.io.IOException("Invalid key length: $clientLen")
                val clientBytes = ByteArray(clientLen)
                dis.readFully(clientBytes)
                val clientPub = String(clientBytes, Charsets.UTF_8)

                // 2. Read Watch's real screen dimensions (sent right after the public key)
                val watchW = dis.readInt().coerceIn(200, 1000)
                val watchH = dis.readInt().coerceIn(200, 1000)
                Log.i(tag, "Watch dimensions received: ${watchW}×${watchH}")
                onWatchDimensions?.invoke(watchW, watchH)

                if (!KeyExchange.validatePeerKey(clientPub)) {
                    Log.e(tag, "❌ مفتاح غير صالح من الساعة (فشل التحقق من المنحنى) — رفض الاتصال")
                    newClient.close()
                    continue
                }

                // Notify phone UI that a watch is trying to connect — show PIN entry screen
                PairingManager.notifyWatchKnocked()

                // 3. Generate Phone's ephemeral key
                val kp = KeyExchange.generateEphemeralKeyPair()
                val pubBytes = kp.publicKeyBase64.toByteArray(Charsets.UTF_8)

                // 4. Send Phone's public key
                dos.writeInt(pubBytes.size)
                dos.write(pubBytes)
                dos.flush()

                // 5. Derive Session Key (pairingCode is included in HKDF info — Fail Closed)
                val sessionKey = KeyExchange.deriveSessionKey(kp.privateKey, clientPub, pairingCode)
                encryptedChannel = EncryptedChannel(sessionKey, "tcp-stream", "phone-to-watch")

                // 6. Read & verify Encrypted Auth Block from Watch
                //    Wrong PIN → mismatched session key → GCM tag check fails → Fail Closed
                val authBlockLen = dis.readInt()
                if (authBlockLen <= 0 || authBlockLen > 256) {
                    Log.e(tag, "❌ Invalid auth block length: $authBlockLen — dropping connection")
                    newClient.close()
                    continue
                }
                val authBlock = ByteArray(authBlockLen)
                dis.readFully(authBlock)

                val verified = KeyExchange.verifyAuthBlock(authBlock, sessionKey)
                if (!verified) {
                    Log.e(tag, "❌ PIN MISMATCH or MITM detected — closing socket immediately")
                    PairingManager.notifyPinRejected()
                    // Send rejection code, then hard-close
                    try {
                        newClient.outputStream.write(byteArrayOf(0x00))
                        newClient.outputStream.flush()
                    } catch (_: Exception) {}
                    newClient.close()
                    continue
                }

                // 7. Send ACK — PIN verified, session is trusted
                dos.writeByte(0x01)
                dos.flush()
                PairingManager.notifyPaired()
                Log.i(tag, "✅ PIN verified. Handshake complete with Watch")


                isClientConnected = true

                // Launch touch receiver thread alongside the sender thread
                Thread({ runInputReceiver(newClient) }, "SL-InputReceiver").apply {
                    priority = Thread.MAX_PRIORITY - 2
                    isDaemon = true
                    start()
                }
            }

            senderThread.interrupt()
        } catch (e: Exception) {
            Log.e(tag, "Server error: ${e.message}")
        }
    }

    private fun runSender() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        var cachedDos: java.io.DataOutputStream? = null
        var cachedForSocket: Socket? = null
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                var task = iFrameQueue.poll()
                if (task == null) task = pFrameQueue.poll()

                if (task == null) {
                    LockSupport.parkNanos(500_000) // 0.5ms lock-free wait
                    continue
                }

                val wire = task.wire!!
                val size = task.size

                val socket = clientSocket
                if (socket == null || socket.isClosed) {
                    WireBufferPool.release(wire)
                    task.wire = null
                    freeTasks.offer(task)
                    cachedDos = null
                    cachedForSocket = null
                    continue
                }
                try {
                    val ec = encryptedChannel
                    if (ec != null) {
                        val encWire = WireBufferPool.acquire()
                        val outSize = ec.encrypt(wire, 0, size, encWire, 0)

                        if (cachedDos == null || cachedForSocket !== socket) {
                            cachedDos = java.io.DataOutputStream(socket.outputStream)
                            cachedForSocket = socket
                        }
                        cachedDos!!.writeInt(outSize)
                        cachedDos!!.write(encWire, 0, outSize)
                        bytesSent.addAndGet(outSize.toLong())
                        WireBufferPool.release(encWire)
                    } else {
                        if (cachedDos == null || cachedForSocket !== socket) {
                            cachedDos = java.io.DataOutputStream(socket.outputStream)
                            cachedForSocket = socket
                        }
                        cachedDos!!.writeInt(size)
                        cachedDos!!.write(wire, 0, size)
                        bytesSent.addAndGet(size.toLong())
                    }
                    onChunkDelivered?.invoke()
                    WireBufferPool.release(wire)
                } catch (e: IOException) {
                    Log.w(tag, "Send failed: ${e.message}")
                    isClientConnected = false
                    cachedDos = null
                    cachedForSocket = null
                    WireBufferPool.release(wire)
                }
                task.wire = null
                freeTasks.offer(task)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    fun sendPooledWire(wire: ByteArray, size: Int): Boolean {
        if (!isClientConnected) {
            WireBufferPool.release(wire)
            return false
        }

        // Extract isKeyframe from header (offset 13 is HDR_FLAGS)
        val flags = wire[StreamProtocol.HDR_FLAGS].toInt()
        val isKeyframe = (flags and 0x01) != 0
        val task = freeTasks.poll() ?: SendTask() // create new if pool empty

        task.wire = wire
        task.size = size
        task.isKeyframe = isKeyframe

        val q = if (isKeyframe) iFrameQueue else pFrameQueue
        if (!q.offer(task)) {
            droppedFrames++
            if (!isKeyframe) {
                // Drop this P-frame immediately to protect queue
                task.wire = null
                freeTasks.offer(task)
                WireBufferPool.release(wire)
                return false
            } else {
                // Critical IDR frame: try to remove an old I-frame to make room
                val old = iFrameQueue.poll()
                if (old != null && old.wire != null) {
                    WireBufferPool.release(old.wire!!)
                    old.wire = null
                    freeTasks.offer(old)
                }
                q.offer(task)
            }
        }
        return true
    }

    private fun closeClient() {
        try { clientSocket?.close() } catch (_: Exception) {}
        clientSocket = null
        isClientConnected = false
        iFrameQueue.clear()
        pFrameQueue.clear()
    }

    fun close() {
        running.set(false)
        closeClient()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        Log.i(tag, "Server closed. totalSent=${bytesSent.get()} bytes")
    }
}
