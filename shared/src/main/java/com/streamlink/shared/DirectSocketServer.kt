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
    class SendTask {
        var wire: ByteArray? = null
        var size: Int = 0
    }
    private val sendQueue = LinkedBlockingQueue<SendTask>(256)
    private val freeTasks = LinkedBlockingQueue<SendTask>(256).apply {
        repeat(256) { offer(SendTask()) }
    }
    private val bytesSent = AtomicLong(0L)

    @Volatile var isClientConnected = false
    var onChunkDelivered: (() -> Unit)? = null

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
                isClientConnected = true
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
        val task = freeTasks.poll()
        if (task == null) {
            // Backpressure: no free tasks
            WireBufferPool.release(wire)
            return false
        }
        task.wire = wire
        task.size = size
        val offered = sendQueue.offer(task)
        if (!offered) {
            task.wire = null
            freeTasks.offer(task)
            WireBufferPool.release(wire)
        }
        return offered
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
