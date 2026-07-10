package com.streamlink.app.core

import android.content.Context
import android.content.Intent
import android.util.Log
import com.streamlink.app.capture.CaptureService
import com.streamlink.app.capture.HardwareEncoder
import com.streamlink.app.stream.MirrorDataPlane
import com.streamlink.shared.DirectSocketServer
import com.streamlink.shared.EventPipeline
import com.streamlink.shared.GlobalStreamState
import com.streamlink.shared.StreamProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * StreamingOrchestrator — the single authority that owns and coordinates
 * all streaming components: encoder, data-plane, recovery, quality control.
 */
class StreamingOrchestrator @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val scope: CoroutineScope,
    private val events: EventPipeline,
    private val socketServer: DirectSocketServer,
    private val streamRouter: com.streamlink.shared.StreamRouter,
    private val mirrorDataPlane: MirrorDataPlane,
    private val hardwareEncoder: HardwareEncoder,
    private val latencyTracker: com.streamlink.shared.LatencyTracker,
    private val thermalMonitor: com.streamlink.shared.ThermalMonitor,
    private val connectionManager: com.streamlink.shared.ConnectionManager
) {
    private val tag = "StreamingOrchestrator"
    
    init {
        streamRouter.socketServer = socketServer
        socketServer.onTouchEvent = { event ->
            // Use the zero-GC ring buffer instead of direct synchronous dispatch
            publishTouch(
                event.phase.wireType,
                event.pointerId,
                event.nx,
                event.ny,
                event.timestampUs
            )
        }
        socketServer.onControlMessage = { msg ->
            when (msg.command) {
                StreamProtocol.CMD_SET_BITRATE -> {
                    Log.i(tag, "AI Reverse Control: Adjusting Bitrate to ${msg.value} kbps")
                    hardwareEncoder.setBitrate(msg.value)
                }
                StreamProtocol.CMD_GLOBAL_ACTION -> {
                    Log.i(tag, "Remote global action: ${msg.value}")
                    com.streamlink.app.control.RemoteControlAccessibilityService.instance?.performGlobalAction(msg.value)
                }
            }
        }
        socketServer.onWatchDimensions = { w, h ->
            // Propagate real watch screen dimensions to the touch mapper immediately
            com.streamlink.app.control.RemoteControlAccessibilityService.instance
                ?.updateWatchDimensions(w, h)
        }

        startRealtimeConsumer()
        startQualityControllerWiring()
        // Phase B: Wire auto-reconnect — ConnectionManager monitors GlobalStreamState
        // and retries with Exponential Backoff when FAILED state is detected.
        connectionManager.watchState(scope) {
            Log.i(tag, "🔄 Auto-reconnect triggered by ConnectionManager")
            scope.launch { socketServer.start() }
            mirrorDataPlane.start(scope)
        }
    }
    
    private var currentWidth = com.streamlink.shared.StreamProtocol.WEAR_W_FULL
    private var currentHeight = com.streamlink.shared.StreamProtocol.WEAR_H_FULL
    private var currentFps = com.streamlink.shared.StreamProtocol.WEAR_FPS_FULL
    private var currentBitrate = com.streamlink.shared.StreamProtocol.WEAR_BPS_FULL

    private val intelEngine = com.streamlink.shared.StreamingIntelligenceEngine(
        scope = scope,
        onBitrateChange = { kbps ->
            currentBitrate = kbps
            hardwareEncoder.setBitrate(kbps)
        },
        onFpsChange = { fps ->
            currentFps = fps
            hardwareEncoder.reconfigure(com.streamlink.shared.ResolutionProfile("CUSTOM", currentWidth, currentHeight, currentFps, currentBitrate))
        },
        onResolutionChange = { scale ->
            currentWidth = (com.streamlink.shared.StreamProtocol.WEAR_W_FULL * scale).toInt()
            currentHeight = (com.streamlink.shared.StreamProtocol.WEAR_H_FULL * scale).toInt()
            hardwareEncoder.reconfigure(com.streamlink.shared.ResolutionProfile("CUSTOM", currentWidth, currentHeight, currentFps, currentBitrate))
        },
        onIFrameIntervalChange = { sec ->
            hardwareEncoder.forceKeyframe()
        }
    )

    private fun startQualityControllerWiring() {
        intelEngine.start()
        scope.launch {
            // ✅ FIX: Only poll when a session is actually active — avoids wasting
            // CPU and battery when the app is idle.
            GlobalStreamState.snapshot.collect { snapshot ->
                if (snapshot.state == GlobalStreamState.State.STREAMING ||
                    snapshot.state == GlobalStreamState.State.DEGRADED) {

                    val report = latencyTracker.report()
                    intelEngine.currentRttMs = report.avgE2EMs
                    intelEngine.jitterMs = report.jitterMs
                    intelEngine.packetLossRate = report.lateFramePct / 100f
                    intelEngine.currentFps = currentFps
                    intelEngine.decoderQueueSize = socketServer.queueDepth
                    intelEngine.thermalLevel = thermalMonitor.thermalLevel.value
                    val realBattery = runCatching {
                        val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE)
                            as? android.os.BatteryManager
                        bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
                    }.getOrDefault(100)
                    intelEngine.batteryLevel = realBattery
                    intelEngine.cpuUsagePercent = 10f
                }
            }
        }
    }
    
    // ==========================================
    // 🔥 ZERO-GC LOCK-FREE TOUCH RING BUFFER
    // ==========================================
    private val ringCapacity = 1024
    private val mask = ringCapacity - 1
    
    private val phaseBuffer = ByteArray(ringCapacity)
    private val pointerBuffer = IntArray(ringCapacity)
    private val nxBuffer = FloatArray(ringCapacity)
    private val nyBuffer = FloatArray(ringCapacity)
    private val timeBuffer = LongArray(ringCapacity)
    
    private val writeIndex = java.util.concurrent.atomic.AtomicInteger(0)
    private val readIndex = java.util.concurrent.atomic.AtomicInteger(0)
    
    @Volatile private var runningRealtime = true
    // ✅ FIX: Track silently dropped touch events for diagnostics
    private val droppedTouchEvents = AtomicLong(0L)

    fun publishTouch(phase: Byte, pointerId: Int, nx: Float, ny: Float, timestampUs: Long) {
        val w = writeIndex.get()
        val next = (w + 1) and mask

        if (next == readIndex.get()) {
            readIndex.incrementAndGet() // Drop oldest
            val dropped = droppedTouchEvents.incrementAndGet()
            if (dropped % 100 == 0L) {
                Log.w(tag, "Touch ring buffer overflow — total dropped: $dropped events")
            }
        }
        
        val idx = w and mask
        phaseBuffer[idx] = phase
        pointerBuffer[idx] = pointerId
        nxBuffer[idx] = nx
        nyBuffer[idx] = ny
        timeBuffer[idx] = timestampUs
        
        writeIndex.lazySet(next)
    }
    
    private fun startRealtimeConsumer() {
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            while (runningRealtime) {
                val r = readIndex.get()
                val w = writeIndex.get()
                
                if (r == w) {
                    // ✅ FIX: 1ms instead of 50µs — eliminates CPU spin burn
                    // while maintaining imperceptible touch latency (<< human perception ~16ms)
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000)
                    continue
                }
                
                val idx = r and mask
                val phaseByte = phaseBuffer[idx]
                val pointerId = pointerBuffer[idx]
                val nx = nxBuffer[idx]
                val ny = nyBuffer[idx]
                val ts = timeBuffer[idx]
                
                processRealtimeTouch(phaseByte, pointerId, nx, ny, ts)
                
                readIndex.lazySet((r + 1) and mask)
            }
        }, "SL-TouchOrchestrator").start()
    }
    
    private fun processRealtimeTouch(phaseByte: Byte, pointerId: Int, nx: Float, ny: Float, ts: Long) {
        // Reconstruct or pass to JNI. For now we reconstruct minimally for Android's Accessibility
        val phase = when(phaseByte) {
            1.toByte() -> com.streamlink.shared.TouchPhase.DOWN
            2.toByte() -> com.streamlink.shared.TouchPhase.MOVE
            3.toByte() -> com.streamlink.shared.TouchPhase.UP
            else -> com.streamlink.shared.TouchPhase.CANCEL
        }
        val event = com.streamlink.shared.TouchEvent(phase, pointerId, nx, ny, 0, ts)
        
        com.streamlink.shared.ai.TouchPerceptionHub.onRealTouch(event)
        com.streamlink.app.control.RemoteControlAccessibilityService.instance?.handle(event)
    }
    // ==========================================

    private var webRtcTransport: com.streamlink.shared.WebRtcTransport? = null

    fun startStream(
        context: Context,
        url: String,
        resultCode: Int,
        projectionData: Intent?,
        isDrm: Boolean,
        networkQuality: Float
    ) {
        Log.i(tag, "Starting stream → url=$url drm=$isDrm nq=$networkQuality")
        scope.launch {
            GlobalStreamState.transition(GlobalStreamState.State.CONNECTING)
        }
        events.sessionStart(java.util.UUID.randomUUID().toString(), StreamProtocol.MODE_MIRROR)

        // 1. Start TCP Server for Watch
        scope.launch { socketServer.start() }

        // 1.5 Start WebRTC Fallback for global reach
        // ✅ FIX: Read signaling URL from BuildConfig so it can be configured
        // per environment (dev/staging/prod) without changing source code.
        val signalingUrl = try {
            com.streamlink.app.BuildConfig.SIGNALING_URL
                .takeIf { it.isNotBlank() } ?: "wss://signaling.streamlink.com"
        } catch (_: Exception) { "wss://signaling.streamlink.com" }

        val signalingClient = com.streamlink.shared.SignalingClient(
            backendUrl = signalingUrl,
            userId = "streamlink_phone_1",
            deviceType = "PHONE"
        )
        // تبادل مفتاح ECDH خاص بقناة HOTC عبر نفس اتصال الـsignaling (منفصل عن مفتاح TCP المحلي)
        var hotcEncryptedChannel: com.streamlink.shared.EncryptedChannel? = null
        val hotcKeyPair = com.streamlink.shared.KeyExchange.generateEphemeralKeyPair()

        val hotcChannel = com.streamlink.shared.network.WebRtcHotcChannel(context).apply {
            onEncryptedFrameReceived = { data ->
                val ec = hotcEncryptedChannel
                val decrypted = if (ec != null) {
                    try { ec.decrypt(data) } catch (e: Exception) {
                        Log.w(tag, "HOTC decrypt failed: ${e.message}"); null
                    }
                } else null

                if (decrypted != null && decrypted.size >= StreamProtocol.INPUT_FRAME_SIZE) {
                    val event = com.streamlink.shared.TouchCodec.decode(decrypted)
                    if (event != null) {
                        com.streamlink.shared.ai.TouchPerceptionHub.onRealTouch(event)
                        com.streamlink.app.control.RemoteControlAccessibilityService.instance?.handle(event)
                    }
                }
            }
        }

        // استقبال مفتاح الساعة العام عبر قناة الـsignaling واشتقاق مفتاح الجلسة
        // ✅ FIX: 30-second timeout on key exchange — prevents permanent CONNECTING state
        scope.launch {
            val keyExchangeResult = withTimeoutOrNull(30_000L) {
                var resolved = false
                signalingClient.messages.collect { msg ->
                    if (msg.optString("type") == "HOTC_KEY") {
                        val peerKey = msg.optString("payload")
                        if (com.streamlink.shared.KeyExchange.validatePeerKey(peerKey)) {
                            // Using the same pairing code acquired by the TCP server for consistency
                            val sessionKey = com.streamlink.shared.KeyExchange.deriveSessionKey(hotcKeyPair, peerKey, socketServer.pairingCode)
                            hotcEncryptedChannel = com.streamlink.shared.EncryptedChannel(sessionKey, "tcp-stream", "phone-to-watch")

                            Log.i(tag, "✅ HOTC session key derived over signaling")
                            resolved = true
                        } else {
                            Log.e(tag, "❌ Rejected invalid HOTC key from peer — will retry")
                        }
                        if (resolved) return@collect
                    }
                }
            }
            if (keyExchangeResult == null) {
                Log.w(tag, "⚠️ HOTC key exchange timed out after 30s — falling back to LAN-only mode")
                // LAN TCP stream continues unaffected; only WebRTC relay is unavailable
            }
        }

        scope.launch {
            signalingClient.connect()
            signalingClient.sendMessage("HOTC_KEY", "broadcast", hotcKeyPair.publicKeyBase64)
        }

        webRtcTransport = com.streamlink.shared.WebRtcTransport(
            context = context,
            signalingClient = signalingClient,
            isOfferer = false, // Phone acts as answerer or offerer depending on role, assume Answerer
            hotcChannel = hotcChannel
        )
        streamRouter.webRtcTransport = webRtcTransport
        webRtcTransport?.initialize()

        // 2. Start Data Plane
        mirrorDataPlane.start(scope)

        // 2.5 Start Metrics Telemetry to Backend
        scope.launch {
            GlobalStreamState.snapshot.collect { state ->
                if (state.fps > 0 || state.bitrateKbps > 0) {
                    val report = latencyTracker.report()
                    val payload = org.json.JSONObject().apply {
                        put("fps", state.fps)
                        put("bitrateKbps", state.bitrateKbps)
                        put("packetLossPercent", report.lateFramePct)
                        // ✅ FIX: Skip latencyMs entirely when no samples — avoids
                        // misleading -1 values in the dashboard / adaptive logic.
                        if (report.avgE2EMs > 0) put("latencyMs", report.avgE2EMs)
                        if (report.jitterMs > 0)  put("jitterMs",  report.jitterMs)
                    }
                    signalingClient.sendMessage("METRICS", "broadcast", payload.toString())
                }
            }
        }

        // 3. Start MediaProjection Capture Service
        // ✅ FIX: Wait for socket server to be ready (up to 3s) before starting
        // capture, ensuring encoder has a destination before producing frames.
        if (projectionData != null) {
            scope.launch {
                val serverReady = withTimeoutOrNull(3_000L) {
                    var attempts = 0
                    while (!socketServer.isRunning && attempts < 30) {
                        kotlinx.coroutines.delay(100)
                        attempts++
                    }
                    socketServer.isRunning
                } ?: false

                if (!serverReady) {
                    Log.w(tag, "⚠️ Socket server not ready after 3s — starting capture anyway")
                }

                val serviceIntent = Intent(context, CaptureService::class.java).apply {
                    action = CaptureService.ACTION_START
                    putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(CaptureService.EXTRA_DATA, projectionData)
                }
                context.startForegroundService(serviceIntent)
                GlobalStreamState.transition(GlobalStreamState.State.STREAM_STARTING)
                GlobalStreamState.transition(GlobalStreamState.State.STREAMING)
            }
        } else {
            scope.launch {
                GlobalStreamState.transition(GlobalStreamState.State.STREAM_STARTING)
                GlobalStreamState.transition(GlobalStreamState.State.STREAMING)
            }
        }
    }

    // Suspend overload for use from coroutines (backward compatibility)
    suspend fun startStream(
        url: String,
        resultCode: Int,
        projectionData: Intent?,
        isDrm: Boolean,
        networkQuality: Float
    ) {
        Log.i(tag, "startStream (no context) → url=$url")
        GlobalStreamState.transition(GlobalStreamState.State.CONNECTING)
        events.sessionStart(java.util.UUID.randomUUID().toString(), StreamProtocol.MODE_MIRROR)
        scope.launch { socketServer.start() }
        mirrorDataPlane.start(scope)
        GlobalStreamState.transition(GlobalStreamState.State.STREAM_STARTING)
        GlobalStreamState.transition(GlobalStreamState.State.STREAMING)
    }

    fun stopStream(context: Context) {
        Log.i(tag, "Stopping stream")
        com.streamlink.shared.ai.TouchPerceptionHub.reset()
        val serviceIntent = Intent(context, CaptureService::class.java).apply {
            action = CaptureService.ACTION_STOP
        }
        context.startService(serviceIntent)
        mirrorDataPlane.stop()
        socketServer.close()
        webRtcTransport?.close()
        webRtcTransport = null
        runningRealtime = false
        scope.launch {
            GlobalStreamState.transition(GlobalStreamState.State.STOPPED)
        }
    }

    // Suspend overload for use from coroutines (backward compatibility)
    suspend fun stopStream() {
        Log.i(tag, "Stopping stream (no context)")
        com.streamlink.shared.ai.TouchPerceptionHub.reset()
        mirrorDataPlane.stop()
        socketServer.close()
        runningRealtime = false
        GlobalStreamState.transition(GlobalStreamState.State.STOPPED)
    }

    fun requestKeyframe() {
        Log.i(tag, "Keyframe requested")
        hardwareEncoder.forceKeyframe()
    }
}
