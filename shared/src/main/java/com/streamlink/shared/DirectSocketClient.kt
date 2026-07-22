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
    private val context: android.content.Context,
    private val discovery: NetworkDiscovery,
    private val port: Int = StreamProtocol.DIRECT_SOCKET_PORT
) : com.streamlink.shared.network.LocalTouchSender {
    private val tag = "DirectSocketClient"
    private var socket: Socket? = null
    private val closed = AtomicBoolean(false)
    private var encryptedChannel: EncryptedChannel? = null
    private val clockSyncEngine = com.streamlink.shared.transport.TimeSynchronizer()

    // Pairing code — set by the Wear UI before attempting to connect.
    // ✅ FIX #5 (أمني): مفيش قيمة افتراضية ("000000") — لو فضل null وقت
    // الـ handshake، الاتصال بيترفض بالكامل (Fail Closed) بدل ما
    // يستمر بصوت بقيمة تخمينية معروفة.
    @Volatile var pairingCode: String? = null

    /**
     * Manual IP fallback — set by the UI when mDNS discovery times out
     * (AP/client isolation networks block multicast silently). Takes priority
     * over discovery.discoveredHost once set. Sticky across reconnects on
     * purpose: a network that blocks multicast won't start allowing it
     * mid-session, so there's no point re-waiting on discovery each retry.
     */
    @Volatile var manualHostOverride: String? = null

    companion object {
        private const val DISCOVERY_TIMEOUT_MS = 15_000L
    }
    data class WireChunk(
        val nalSeq: Int,           // ✅ Global NAL sequence (for FrameAssembler grouping)
        val chunkIdx: Int,         // ✅ Chunk index within this NAL (0..totalChunks-1)
        val totalChunks: Int,
        val timestampUs: Long,     // ✅ From wire header (correct PTS)
        val deadlineUs: Long,      // ✅ Monotonic deadline for dropping frames
        val isKeyframe: Boolean,
        val nalType: Int,
        val data: ByteArray,       // Slice — caller must NOT hold ref after onChunk returns
        val dataSize: Int
    )

    suspend fun connect(
        onStateChange: (Boolean) -> Unit,
        onChunk: ((WireChunk) -> Unit)? = null,
        onControlMessage: ((ControlCodec.ControlMessage) -> Unit)? = null,
        onDiscoveryTimedOut: (() -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        discovery.startDiscovery()
        var attempt = 0
        val maxAttempts = 10
        val discoveryStartMs = System.currentTimeMillis()
        var timeoutFired = false

        while (!closed.get() && attempt < maxAttempts) {
            val host = manualHostOverride ?: discovery.discoveredHost.value
            if (host == null) {
                val cachedHost = context.getSharedPreferences("StreamLinkPrefs", android.content.Context.MODE_PRIVATE).getString("last_host", null)
                
                if (cachedHost != null) {
                    Log.i(tag, "mDNS not ready, trying cached host: $cachedHost")
                    // We will try cachedHost later in the loop if discovery is still null, but let's assign it here.
                }

                if (!timeoutFired &&
                    System.currentTimeMillis() - discoveryStartMs >= DISCOVERY_TIMEOUT_MS
                ) {
                    timeoutFired = true
                    Log.w(tag, "mDNS discovery timed out after ${DISCOVERY_TIMEOUT_MS}ms — no phone found, waiting for manual IP or late discovery")
                    onDiscoveryTimedOut?.invoke()
                }
                
                if (cachedHost != null) {
                    // Try the cached host immediately if we don't have one
                    // But we don't want to infinite loop on it if it's dead.
                    // We'll let the host var be cachedHost for this attempt if attempt > 2 (give mDNS 2 secs)
                }
                
                val finalHost = if (attempt > 1 && cachedHost != null) cachedHost else { delay(1000); continue }
                // Re-evaluate host with finalHost
            }
            val finalHostToUse = host ?: context.getSharedPreferences("StreamLinkPrefs", android.content.Context.MODE_PRIVATE).getString("last_host", null)
            
            if (finalHostToUse == null) {
                 delay(1000)
                 continue
            }
            attempt++
            Log.i(tag, "Connect attempt $attempt/$maxAttempts → $finalHostToUse:$port")
            try {
                val s = Socket().apply {
                    connect(InetSocketAddress(finalHostToUse, port), 5_000)
                    tcpNoDelay = true
                    keepAlive = true
                    reuseAddress = true
                    trafficClass = 0x10
                    sendBufferSize = 32 * 1024
                    receiveBufferSize = 32 * 1024
                    soTimeout = 15_000
                    setPerformancePreferences(0, 2, 1)  // latency > bandwidth > connection time
                }
                socket = s
                
                // ECDH Handshake
                val kp = KeyExchange.generateEphemeralKeyPair()
                val pubBytes = kp.publicKeyBase64.toByteArray(Charsets.UTF_8)
                val dos = java.io.DataOutputStream(s.outputStream)
                val dis = java.io.DataInputStream(s.inputStream)
                
                // 1. Handshake: Exchange Protocol Versions
                dos.writeByte(StreamProtocol.PROTOCOL_VERSION.toInt())
                dos.flush()
                val serverVersion = dis.readByte()
                if (serverVersion != StreamProtocol.PROTOCOL_VERSION) {
                    Log.e(tag, "❌ Unsupported protocol version from phone: $serverVersion (Expected: ${StreamProtocol.PROTOCOL_VERSION})")
                    s.close()
                    delay(1000)
                    continue
                }

                // 2. Send watch's public key
                dos.writeInt(pubBytes.size)
                dos.write(pubBytes)

                // 2. Send watch screen dimensions for accurate touch mapping
                val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE)
                        as android.view.WindowManager
                val displayMetrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(displayMetrics)
                dos.writeInt(displayMetrics.widthPixels)
                dos.writeInt(displayMetrics.heightPixels)
                dos.flush()
                
                val theirLen = dis.readInt()
                val theirBytes = ByteArray(theirLen)
                dis.readFully(theirBytes)
                val theirPub = String(theirBytes, Charsets.UTF_8)

                if (!KeyExchange.validatePeerKey(theirPub)) {
                    Log.e(tag, "❌ مفتاح غير صالح من الموبايل (فشل التحقق من المنحنى) — إغلاق الاتصال")
                    s.close()
                    delay(1000)
                    continue
                }

                // ✅ FIX #5: Fail Closed — لو مفيش pairingCode متسجل، إرفض الاتصال
                val code = pairingCode
                if (code == null) {
                    Log.e(tag, "\u274c رفض الاتصال — لم يتم تعيين كود الاقتران (Fail Closed)")
                    s.close()
                    delay(2000)
                    continue
                }

                val sessionKey = KeyExchange.deriveSessionKey(kp.privateKey, theirPub, code)
                encryptedChannel = EncryptedChannel(sessionKey, "tcp-stream", "W2P", "P2W")

                // 6. Send Encrypted Auth Block for Fail Closed PIN verification
                //    Phone will verify this — if PIN is wrong, decryption produces garbage
                //    and phone closes the socket immediately.
                val authBlock = KeyExchange.encryptAuthBlock(sessionKey)
                dos.writeInt(authBlock.size)
                dos.write(authBlock)
                dos.flush()

                // 7. Wait for phone's ACK (0x01 = verified, 0x00 = rejected)
                val ack = dis.readByte()
                if (ack != 0x01.toByte()) {
                    Log.e(tag, "❌ PIN rejected by phone. Closing connection.")
                    s.close()
                    delay(2000)
                    continue
                }

                // 8. Multi-Ping NTP-style Time Synchronization Handshake
                // Send 5 PINGs, receive 5 PONGs to find minimum RTT for offset calculation
                for (i in 0 until com.streamlink.shared.transport.SyncProtocol.HANDSHAKE_PINGS) {
                    val t1 = System.nanoTime()
                    val pingBytes = com.streamlink.shared.transport.SyncProtocol.createPingPacket(t1)
                    dos.writeInt(pingBytes.size)
                    dos.write(pingBytes)
                    dos.flush()

                    val pongLen = dis.readInt()
                    val pongBytes = ByteArray(pongLen)
                    dis.readFully(pongBytes)
                    val pong = com.streamlink.shared.transport.SyncProtocol.parsePongPacket(pongBytes)
                    val t4 = System.nanoTime()

                    clockSyncEngine.updateOffset(pong.t1, pong.t2, pong.t3, t4)
                }

                Log.i(tag, "✅ Connected and Handshake complete with $finalHostToUse:$port")
                context.getSharedPreferences("StreamLinkPrefs", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putString("last_host", finalHostToUse)
                    .apply()
                onStateChange(true)
                attempt = 0
                
                // Launch touch sender as a sibling coroutine alongside the receive loop
                startTouchSenderOnce()
                receiveLoop(s.inputStream, onChunk, onControlMessage)
                
                onStateChange(false)
            } catch (e: IOException) {
                Log.w(tag, "Connection failed: ${e.message}")
                delay(minOf(500L * attempt, 8_000L))
            }
        }
        onStateChange(false)
        Log.e(tag, "Max reconnect attempts exceeded")
        discovery.stopDiscovery()
    }

    private fun receiveLoop(
        stream: java.io.InputStream,
        onChunk: ((WireChunk) -> Unit)?,
        onControlMessage: ((ControlCodec.ControlMessage) -> Unit)? = null
    ) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        val dis = java.io.DataInputStream(stream)
        val dataBuf = ByteArray(StreamProtocol.WIRE_BUFFER_SIZE)
        val encryptedBuf = ByteArray(StreamProtocol.WIRE_BUFFER_SIZE)
        val decryptedBuf = ByteArray(StreamProtocol.WIRE_BUFFER_SIZE)

        try {
            while (!closed.get()) {
                // 1. Read 4-byte length prefix
                val len = dis.readInt()
                if (len <= 0 || len > encryptedBuf.size) {
                    Log.e(tag, "Invalid packet length=$len")
                    return
                }

                dis.readFully(encryptedBuf, 0, len)
                
                val ec = encryptedChannel
                val decrypted: ByteArray
                val decryptedSize: Int
                if (ec != null) {
                    try {
                        decryptedSize = ec.decrypt(encryptedBuf, 0, len, decryptedBuf, 0)
                        decrypted = decryptedBuf
                    } catch (e: Exception) {
                        Log.e(tag, "Decryption error: ${e.message}")
                        continue
                    }
                } else {
                    decryptedSize = len
                    decrypted = encryptedBuf
                }
                
                if (decryptedSize < StreamProtocol.WIRE_HEADER_SIZE) continue

                val buffer = java.nio.ByteBuffer.wrap(decrypted, 0, decryptedSize).order(java.nio.ByteOrder.BIG_ENDIAN)

                val magic = buffer.getInt()

                // ✅ رسايل تحكم من الموبايل (زي تحديث jitter-buffer) بتستخدم نفس
                // الـ wire المرقّم بالطول بس بـ magic مختلف. لازم نتعامل معاها هنا
                // ونعمل continue — مش نسيبها توصل للـ fatal check تحت اللي بيقفل الاتصال.
                if (magic == StreamProtocol.MAGIC_NUMBER_CONTROL) {
                    if (decryptedSize >= 10) {
                        val version = buffer.get()
                        if (version == StreamProtocol.PROTOCOL_VERSION) {
                            val command = buffer.get().toInt()
                            val value = buffer.getInt()
                            onControlMessage?.invoke(ControlCodec.ControlMessage(command, value))
                        }
                    }
                    continue
                }

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
                val deadlineUs  = buffer.getLong()
                val localDeadlineUs = clockSyncEngine.toLocalNanoTime(deadlineUs * 1000) / 1000

                // 3. Validate before reading
                if (payloadSize <= 0 || (StreamProtocol.WIRE_HEADER_SIZE + payloadSize > decryptedSize)) {
                    Log.e(tag, "Invalid payloadSize=$payloadSize — protocol error, closing")
                    return
                }

                // 4. Copy payload from decrypted buffer
                System.arraycopy(decrypted, StreamProtocol.WIRE_HEADER_SIZE, dataBuf, 0, payloadSize)

                // 5. Deliver to FrameAssembler — dataBuf is reused so caller MUST copy data if holding
                onChunk?.invoke(
                    WireChunk(
                        nalSeq      = nalSeq,
                        chunkIdx    = chunkIdx,
                        totalChunks = totalChunks,
                        timestampUs = timestampUs,
                        deadlineUs  = localDeadlineUs,
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
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
            var cachedDos: java.io.DataOutputStream? = null
            var cachedForSocket: Socket? = null
            while (!closed.get()) {
                val task = touchSendQueue.poll()
                if (task == null) {
                    LockSupport.parkNanos(500_000)
                    continue
                }
                val s = socket
                if (task.hasData && s != null && !s.isClosed) {
                    try {
                        val ec = encryptedChannel
                        if (ec != null) {
                            val encrypted = ec.encrypt(task.wire)
                            // نعيد استخدام نفس الـDataOutputStream طول عمر الاتصال بدل تخصيص جديد كل touch event
                            if (cachedDos == null || cachedForSocket !== s) {
                                cachedDos = java.io.DataOutputStream(s.outputStream)
                                cachedForSocket = s
                            }
                            val dos = cachedDos ?: throw java.io.IOException("DataOutputStream became null after initialization")
                            dos.writeInt(encrypted.size)
                            dos.write(encrypted)
                        } else {
                            s.outputStream.write(task.wire)
                        }
                        // TCP_NODELAY is enabled, so no flush needed
                    } catch (e: IOException) {
                        Log.w(tag, "Touch send failed: ${e.message}")
                        cachedDos = null
                        cachedForSocket = null
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
    override fun sendTouch(
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
    override fun isConnected(): Boolean {
        val s = socket
        return s != null && s.isConnected && !s.isClosed && encryptedChannel != null
    }

    fun close() {
        closed.set(true)
        runCatching { socket?.close() }
        socket = null
    }
}
