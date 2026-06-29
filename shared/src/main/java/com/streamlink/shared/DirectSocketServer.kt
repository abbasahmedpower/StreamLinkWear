package com.streamlink.shared

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Non-blocking TCP server for H.264 chunk delivery.
 * Uses a dedicated sender thread (LinkedBlockingQueue) to avoid
 * blocking the encoder on TCP write.
 */
class DirectSocketServer {
    private val tag = "DirectSocket"

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private val running = AtomicBoolean(false)
    class SendTask : Comparable<SendTask> {
        var wire: ByteArray? = null
        var size: Int = 0
        var isKeyframe: Boolean = false
        var sequence: Long = 0L

        override fun compareTo(other: SendTask): Int {
            if (this.isKeyframe && !other.isKeyframe) return -1
            if (!this.isKeyframe && other.isKeyframe) return 1
            return this.sequence.compareTo(other.sequence)
        }
    }
    
    private val sendQueue = java.util.concurrent.PriorityBlockingQueue<SendTask>(256)
    private val sendQueueCapacity = 256
    private val taskSeq = AtomicLong(0)
    
    private val freeTasks = LinkedBlockingQueue<SendTask>(256).apply {
        repeat(256) { offer(SendTask()) }
    }
    private val bytesSent = AtomicLong(0L)

    @Volatile var isClientConnected = false
    var onChunkDelivered: (() -> Unit)? = null
    var onTouchEvent: ((TouchEvent) -> Unit)? = null
    private var encryptedChannel: EncryptedChannel? = null

    /** Launched alongside the sender thread when a client connects. */
    private fun runInputReceiver(socket: java.net.Socket) {
        val dis = java.io.DataInputStream(socket.inputStream)
        try {
            while (running.get() && !socket.isClosed) {
                // Read 4-byte length prefix
                val len = dis.readInt()
                if (len <= 0 || len > 1024) continue // basic sanity check
                
                val encryptedBuf = ByteArray(len)
                dis.readFully(encryptedBuf)
                
                val ec = encryptedChannel
                val decrypted = if (ec != null) ec.decrypt(encryptedBuf) else encryptedBuf
                if (decrypted == null || decrypted.size < StreamProtocol.INPUT_FRAME_SIZE) continue
                
                val event = TouchCodec.decode(decrypted)
                if (event != null) {
                    onTouchEvent?.invoke(event)
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
                
                // ECDH Handshake
                val dis = java.io.DataInputStream(newClient.inputStream)
                val dos = java.io.DataOutputStream(newClient.outputStream)
                
                // 1. Read Watch's public key
                val clientLen = dis.readInt()
                val clientBytes = ByteArray(clientLen)
                dis.readFully(clientBytes)
                val clientPub = String(clientBytes, Charsets.UTF_8)
                
                // 2. Generate Phone's ephemeral key
                val kp = KeyExchange.generateEphemeralKeyPair()
                val pubBytes = kp.publicKeyBase64.toByteArray(Charsets.UTF_8)
                
                // 3. Send Phone's public key
                dos.writeInt(pubBytes.size)
                dos.write(pubBytes)
                dos.flush()
                
                // 4. Derive Session Key
                val sessionKey = KeyExchange.deriveSessionKey(kp.privateKey, clientPub)
                encryptedChannel = EncryptedChannel(sessionKey)
                Log.i(tag, "✅ Handshake complete with Watch")

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
        while (!Thread.currentThread().isInterrupted) {
            try {
                val task = sendQueue.take()
                val wire = task.wire!!
                val size = task.size
                
                val socket = clientSocket
                if (socket == null || socket.isClosed) {
                    WireBufferPool.release(wire)
                    task.wire = null
                    freeTasks.offer(task)
                    continue
                }
                try {
                    socket.outputStream.write(wire, 0, size)
                    bytesSent.addAndGet(size.toLong())
                    onChunkDelivered?.invoke()
                    WireBufferPool.release(wire)
                } catch (e: IOException) {
                    Log.w(tag, "Send failed: ${e.message}")
                    isClientConnected = false
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
        task.sequence = taskSeq.getAndIncrement()

        if (sendQueue.size >= sendQueueCapacity) {
            if (!isKeyframe) {
                // Drop this P-frame immediately to protect queue
                task.wire = null
                freeTasks.offer(task)
                WireBufferPool.release(wire)
                return false
            } else {
                // Critical IDR frame: try to remove an old P-frame to make room
                val removed = sendQueue.removeIf { !it.isKeyframe }
                if (!removed) {
                    // Extreme emergency: drop the oldest frame anyway
                    val old = sendQueue.poll()
                    if (old != null && old.wire != null) {
                        WireBufferPool.release(old.wire!!)
                        old.wire = null
                        freeTasks.offer(old)
                    }
                }
            }
        }
        sendQueue.offer(task)
        return true
    }

    private fun closeClient() {
        try { clientSocket?.close() } catch (_: Exception) {}
        clientSocket = null
        isClientConnected = false
        sendQueue.clear()
    }

    fun close() {
        running.set(false)
        closeClient()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        Log.i(tag, "Server closed. totalSent=${bytesSent.get()} bytes")
    }

    val queueDepth: Int get() = sendQueue.size
}
