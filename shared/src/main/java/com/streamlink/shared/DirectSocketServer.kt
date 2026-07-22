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
import com.streamlink.shared.util.LockFreeMpmcQueue
import com.streamlink.shared.security.PairingAttemptThrottle

/**
 * Non-blocking TCP server for H.264 chunk delivery.
 * Uses a dedicated sender thread (LockFreeMpmcQueue) to avoid
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
    
    @Volatile var isTransportPaused = false
    
    /** True once the server socket is bound and listening. Used by orchestrator startup sequencing. */
    val isRunning: Boolean get() = running.get()

    class SendTask {
        var wire: ByteArray? = null
        var size: Int = 0
        var isKeyframe: Boolean = false
    }

    // ✅ FIX #1: MPMC بدل SPSC — الكيوهات دي بيتكتب/يتقرا منها فعليًا من
    // أكتر من Thread (audio capture thread + video mirror thread + الـ
    // eviction path جوه sendPooledWire نفسها).
    private val iFrameQueue = LockFreeMpmcQueue<SendTask>(64)
    private val pFrameQueue = LockFreeMpmcQueue<SendTask>(256)
    private val freeTasks = LockFreeMpmcQueue<SendTask>(512).apply {
        repeat(512) { offer(SendTask()) }
    }
    val bytesSent = AtomicLong(0L)

    private data class ControlTask(val payload: ByteArray)
    private val controlQueue = LockFreeMpmcQueue<ControlTask>(16)

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

    /** Called when the watch successfully authenticates and connects. */
    var onClientConnected: ((name: String, ip: String) -> Unit)? = null

    /**
     * Pairing code injected by the Phone UI. يجب ضبطه قبل أي اتصال.
     * ✅ FIX #5 (أمني): مفيش قيمة افتراضية ("000000") بعد كده — لو
     * فضل null وقت الـ handshake، الاتصال بيترفض بالكامل (Fail Closed)
     * بدل ما يستمر بقيمة تخمينية معروفة.
     */
    @Volatile var pairingCode: String? = null

    // ✅ FIX #2: @Volatile — الحقل ده بيتكتب في accept-thread وبيتقرا في
    // runSender() (اللي بيبدأ شغال *قبل* ما الـ handshake يخلص، يعني
    // من غير @Volatile مفيش ضمان JMM إنه هيشوف القيمة الجديدة — ثغرة
    // ممكن تخلي الفيديو يتبعت من غير تشفير بصمت في أسوأ سيناريو).
    @Volatile private var encryptedChannel: EncryptedChannel? = null

    private val pairingThrottle = PairingAttemptThrottle()

    private fun runInputReceiver(socket: java.net.Socket) {
        val dis = java.io.DataInputStream(socket.inputStream)
        try {
            while (running.get() && !socket.isClosed) {
                val len = dis.readInt()
                if (len <= 0 || len > 1024) break

                val encryptedBuf = ByteArray(len)
                dis.readFully(encryptedBuf)

                val ec = encryptedChannel
                val decrypted = try {
                    if (ec != null) ec.decrypt(encryptedBuf) else encryptedBuf
                } catch (e: Exception) {
                    Log.w(tag, "Decrypt failed, dropping malformed packet: ${e.message}")
                    continue
                }
                if (decrypted == null || decrypted.size < StreamProtocol.INPUT_FRAME_SIZE) continue

                try {
                    val buf = java.nio.ByteBuffer.wrap(decrypted).order(java.nio.ByteOrder.BIG_ENDIAN)
                    val magic = buf.getInt()
                    if (magic == StreamProtocol.MAGIC_NUMBER_INPUT) {
                        TouchCodec.decode(decrypted)?.let { onTouchEvent?.invoke(it) }
                    } else if (magic == StreamProtocol.MAGIC_NUMBER_CONTROL) {
                        ControlCodec.decode(decrypted)?.let { onControlMessage?.invoke(it) }
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Malformed input frame ignored: ${e.message}")
                }
            }
        } catch (e: IOException) {
            android.util.Log.w(tag, "Input receive loop ended: ${e.message}")
        } catch (e: Exception) {
            android.util.Log.e(tag, "Input receiver crashed unexpectedly: ${e.message}")
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

                try {
                    newClient.tcpNoDelay = true
                    newClient.keepAlive = true
                    newClient.reuseAddress = true
                    newClient.trafficClass = 0x10
                    newClient.sendBufferSize = 32 * 1024
                    newClient.receiveBufferSize = 32 * 1024
                    newClient.setPerformancePreferences(0, 1, 2)
                    newClient.soTimeout = 8_000

                    val remote = newClient.inetAddress.hostAddress ?: "unknown"
                    Log.i(tag, "Watch connected: $remote")

                    val waitMs = pairingThrottle.msUntilAllowed(remote)
                    if (waitMs != null) {
                        Log.w(tag, "⛔ Pairing throttled for $remote — retry after ${waitMs}ms")
                        newClient.close()
                        continue
                    }

                    closeClient()
                    clientSocket = newClient

                    val dis = java.io.DataInputStream(newClient.inputStream)
                    val dos = java.io.DataOutputStream(newClient.outputStream)

                    val clientVersion = dis.readByte()
                    if (clientVersion != StreamProtocol.PROTOCOL_VERSION) {
                        Log.e(tag, "❌ Unsupported protocol version from watch: $clientVersion (Expected: ${StreamProtocol.PROTOCOL_VERSION})")
                        newClient.close()
                        continue
                    }
                    dos.writeByte(StreamProtocol.PROTOCOL_VERSION.toInt())
                    dos.flush()

                    val clientLen = dis.readInt()
                    if (clientLen <= 0 || clientLen > 8192) throw java.io.IOException("Invalid key length: $clientLen")
                    val clientBytes = ByteArray(clientLen)
                    dis.readFully(clientBytes)
                    val clientPub = String(clientBytes, Charsets.UTF_8)

                    val watchW = dis.readInt().coerceIn(200, 1000)
                    val watchH = dis.readInt().coerceIn(200, 1000)
                    Log.i(tag, "Watch dimensions received: ${watchW}×${watchH}")
                    onWatchDimensions?.invoke(watchW, watchH)

                    if (!KeyExchange.validatePeerKey(clientPub)) {
                        Log.e(tag, "❌ مفتاح غير صالح من الساعة — رفض الاتصال")
                        newClient.close()
                        continue
                    }

                    // ✅ FIX #5: Fail Closed — لو مفيش pairingCode متسجل، إرفض
                    // الاتصال تمامًا بدل ما تكمل بقيمة افتراضية ضعيفة.
                    val code = pairingCode
                    if (code == null) {
                        Log.e(tag, "\u274c رفض الاتصال — لم يتم تعيين كود الاقتران بعد (Fail Closed)")
                        newClient.close()
                        continue
                    }

                    PairingManager.notifyWatchKnocked()

                    val kp = KeyExchange.generateEphemeralKeyPair()
                    val pubBytes = kp.publicKeyBase64.toByteArray(Charsets.UTF_8)

                    dos.writeInt(pubBytes.size)
                    dos.write(pubBytes)
                    dos.flush()

                    val sessionKey = KeyExchange.deriveSessionKey(kp.privateKey, clientPub, code)
                    encryptedChannel = EncryptedChannel(sessionKey, "tcp-stream", "P2W", "W2P")

                    val authBlockLen = dis.readInt()
                    if (authBlockLen <= 0 || authBlockLen > 256) {
                        Log.e(tag, "❌ Invalid auth block length: $authBlockLen")
                        newClient.close()
                        continue
                    }
                    val authBlock = ByteArray(authBlockLen)
                    dis.readFully(authBlock)

                    val verified = KeyExchange.verifyAuthBlock(authBlock, sessionKey)
                    if (!verified) {
                        pairingThrottle.recordFailure(remote)
                        Log.e(tag, "❌ PIN MISMATCH or MITM detected")
                        PairingManager.notifyPinRejected()
                        try {
                            newClient.outputStream.write(byteArrayOf(0x00))
                            newClient.outputStream.flush()
                        } catch (e: Exception) {
                            /* intentional: socket may be half-closed or in error state; best-effort rejection notification */
                            Log.d(tag, "Could not send rejection byte: ${e.message}")
                        }
                        newClient.close()
                        continue
                    }

                    dos.writeByte(0x01)
                    dos.writeLong(System.nanoTime())
                    dos.flush()
                    pairingThrottle.recordSuccess(remote)
                    PairingManager.notifyPaired()
                    onClientConnected?.invoke("Watch ($remote)", remote)
                    Log.i(tag, "✅ PIN verified. Handshake complete with Watch")

                    newClient.soTimeout = 0
                    isClientConnected = true

                    // ✅ FIX #6: Process.setThreadPriority بدل Thread.priority — تأثيره
                    // حقيقي على ART عكس Java Thread priority الضعيف. خيط استقبال
                    // اللمس محتاج أولوية فعلية عشان الـ touch latency يكون منخفض.
                    Thread({
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
                        runInputReceiver(newClient)
                    }, "SL-InputReceiver").apply {
                        isDaemon = true
                        start()
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Handshake failed, dropping this client only: ${e.message}")
                    try {
                        newClient.close()
                    } catch (closeErr: Exception) {
                        /* intentional: socket cleanup on error path; resource will be GC'd if close fails */
                        Log.d(tag, "Could not close failed client: ${closeErr.message}")
                    }
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
            // 1. إذا كان النقل متوقفاً مؤقتاً بسبب الـ Handover، انتظر قليلاً ولا تسحب من الـ Queue
            if (isTransportPaused) {
                LockSupport.parkNanos(50_000_000) // 50ms wait
                continue
            }
            try {
                // ✅ رسايل التحكم (زي jitter-buffer target) بيتاخد لها أولوية —
                // صغيرة وحساسة للتوقيت، ولازم تعدي من نفس الـ thread ده بالظبط.
                val ctrl = controlQueue.poll()
                if (ctrl != null) {
                    val socket = clientSocket
                    if (socket != null && !socket.isClosed) {
                        try {
                            if (cachedDos == null || cachedForSocket !== socket) {
                                cachedDos = java.io.DataOutputStream(socket.outputStream)
                                cachedForSocket = socket
                            }
                            val dos = cachedDos ?: continue
                            val ec = encryptedChannel
                            if (ec != null) {
                                val encWire = WireBufferPool.acquire()
                                val outSize = ec.encrypt(ctrl.payload, 0, ctrl.payload.size, encWire, 0)
                                dos.writeInt(outSize)
                                dos.write(encWire, 0, outSize)
                                WireBufferPool.release(encWire)
                            } else {
                                dos.writeInt(ctrl.payload.size)
                                dos.write(ctrl.payload)
                            }
                        } catch (e: IOException) {
                            Log.w(tag, "Control send failed: ${e.message}")
                            cachedDos = null
                            cachedForSocket = null
                        }
                    }
                    continue
                }

                var task = iFrameQueue.poll()
                if (task == null) task = pFrameQueue.poll()

                if (task == null) {
                    LockSupport.parkNanos(500_000) // 0.5ms lock-free wait
                    continue
                }

                // ✅ NULL-SAFE: task.wire is guaranteed non-null here by the enqueue contract
                // (enqueue always assigns wire before adding to queue), but we guard
                // defensively to avoid NPE under memory pressure.
                val wire = task.wire ?: run {
                    freeTasks.offer(task)
                    Log.e(tag, "Dequeued task with null wire — skipping (pool integrity issue)")
                    continue
                }
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
                        val outSize = ec.encryptSelective(wire, 0, size, encWire, 0, task.isKeyframe)

                        if (cachedDos == null || cachedForSocket !== socket) {
                            cachedDos = java.io.DataOutputStream(socket.outputStream)
                            cachedForSocket = socket
                        }
                        val dos = cachedDos ?: run {
                            WireBufferPool.release(encWire)
                            throw java.io.IOException("DataOutputStream became null after initialization")
                        }
                        dos.writeInt(outSize)
                        dos.write(encWire, 0, outSize)
                        bytesSent.addAndGet(outSize.toLong())
                        WireBufferPool.release(encWire)
                    } else {
                        if (cachedDos == null || cachedForSocket !== socket) {
                            cachedDos = java.io.DataOutputStream(socket.outputStream)
                            cachedForSocket = socket
                        }
                        val dos = cachedDos ?: throw java.io.IOException("DataOutputStream became null after initialization")
                        dos.writeInt(size)
                        dos.write(wire, 0, size)
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
                if (old != null) {
                    val oldWire = old.wire
                    if (oldWire != null) {
                        WireBufferPool.release(oldWire)
                        old.wire = null
                    }
                    freeTasks.offer(old)
                }
                if (!q.offer(task)) {
                    // لو لسه مليانة رغم الـ eviction (سباق نادر) — منعًا لتسريب wire buffer
                    val taskWire = task.wire
                    if (taskWire != null) WireBufferPool.release(taskWire)
                    task.wire = null
                    freeTasks.offer(task)
                    droppedFrames++
                    Log.e(tag, "I-frame dropped even after eviction — queue still saturated")
                }
            }
        }
        return true
    }

    /**
     * يبعت أمر تحكم للساعة (phone → watch). بيتمرر عبر نفس الـ sender thread
     * بتاع الفيديو عشان مايحصلش تداخل كتابة على نفس الـ DataOutputStream
     * من تريدين مختلفين في نفس الوقت.
     */
    @Synchronized
    fun sendControlToWatch(command: Int, value: Int) {
        if (!isClientConnected) return
        val payload = ByteArray(10)
        ControlCodec.encodeDirect(command, value, payload)
        if (!controlQueue.offer(ControlTask(payload))) {
            Log.w(tag, "Control queue full — dropping command=$command")
        }
    }

    private fun closeClient() {
        try {
            clientSocket?.close()
        } catch (e: Exception) {
            /* intentional: resource cleanup path; best-effort close of socket */
            Log.d(tag, "Error closing client socket: ${e.message}")
        }
        clientSocket = null
        isClientConnected = false
        iFrameQueue.clear()
        pFrameQueue.clear()
    }

    fun close() {
        running.set(false)
        closeClient()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            /* intentional: server socket cleanup on shutdown; resource will be released */
            Log.d(tag, "Error closing server socket: ${e.message}")
        }
        serverSocket = null
        Log.i(tag, "Server closed. totalSent=${bytesSent.get()} bytes")
    }

    /**
     * إيقاف مؤقت للإرسال لمنع الـ Thread من محاولة الكتابة في سوكيت ميت
     */
    fun pauseTransport() {
        isTransportPaused = true
        Log.w(tag, "Transport pipeline PAUSED for dynamic migration.")
    }

    /**
     * نقل السوكيت حياً إلى المسار الجديد (WiFi P2P أو WAN Cellular).
     * ✅ FIX #4: أصبحت suspend fun — بعد ما كانت بتنادي runBlocking{}
     * جوه Dispatchers.Default thread، وده كان بيجمّد خيط من الـ pool
     * المحدود لحد ما الـ Mutex يفضى، وممكن يعمل deadlock لو حصل
     * handover تاني في نفس اللحظة. دلوقتي مفيش أي blocking خالص.
     */
    suspend fun migrateTransportSocket(newHost: String, newPort: Int, isRelay: Boolean): Boolean {
        return try {
            // 1. إغلاق السوكيت القديم بأمان لمنع الـ Resource Leaks
            clientSocket?.runCatching { close() }

            Log.d(tag, "Establishing new socket connection to $newHost:$newPort")

            // 2. إنشاء اتصال سوكيت جديد بالمسار الجديد
            val newSocket = java.net.Socket()
            newSocket.connect(java.net.InetSocketAddress(newHost, newPort), 5000)
            newSocket.tcpNoDelay = true
            newSocket.keepAlive = true
            newSocket.reuseAddress = true
            newSocket.trafficClass = 0x10
            newSocket.sendBufferSize = 32 * 1024
            newSocket.receiveBufferSize = 32 * 1024
            newSocket.setPerformancePreferences(0, 1, 2)
            newSocket.soTimeout = 0

            this.clientSocket = newSocket
            this.isClientConnected = true

            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
                runInputReceiver(newSocket)
            }, "SL-InputReceiver-Migrated").apply {
                isDaemon = true
                start()
            }

            // 3. فك تجميد حلقة الإرسال لتستأنف عملها فوراً
            isTransportPaused = false

            // 4. ✅ FIX #4: نداء مباشر بدون runBlocking — الدالة نفسها suspend الآن
            GlobalStreamState.update {
                this.copy(bitrateKbps = if (isRelay) 800 else 2500)
            }

            Log.i(tag, "Successfully migrated socket to ${if (isRelay) "WAN" else "Local WiFi"}")
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to migrate socket to $newHost", e)
            false
        }
    }
}
