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
import javax.inject.Inject

/**
 * StreamingOrchestrator — the single authority that owns and coordinates
 * all streaming components: encoder, data-plane, recovery, quality control.
 */
class StreamingOrchestrator @Inject constructor(
    private val scope: CoroutineScope,
    private val events: EventPipeline,
    private val socketServer: DirectSocketServer,
    private val streamRouter: com.streamlink.shared.StreamRouter,
    private val mirrorDataPlane: MirrorDataPlane,
    private val hardwareEncoder: HardwareEncoder
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
            if (msg.command == StreamProtocol.CMD_SET_BITRATE) {
                Log.i(tag, "AI Reverse Control: Adjusting Bitrate to ${msg.value} kbps")
                hardwareEncoder.setBitrate(msg.value)
            }
        }
        
        startRealtimeConsumer()
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
    
    fun publishTouch(phase: Byte, pointerId: Int, nx: Float, ny: Float, timestampUs: Long) {
        val w = writeIndex.get()
        val next = (w + 1) and mask
        
        if (next == readIndex.get()) {
            readIndex.incrementAndGet() // Drop oldest
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
                    java.util.concurrent.locks.LockSupport.parkNanos(50_000) // 50 micro-seconds spin
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
        val signalingClient = com.streamlink.shared.SignalingClient(
            backendUrl = "wss://signaling.streamlink.com",
            userId = "streamlink_phone_1",
            deviceType = "PHONE"
        )
        val hotcChannel = com.streamlink.shared.network.WebRtcHotcChannel(context).apply {
            onEncryptedFrameReceived = { data ->
                // Assuming data is TouchFrame, we route it similarly
                // For now, we decode it directly (simplification of HOTC parsing)
                if (data.size == 27) {
                    val event = com.streamlink.shared.TouchCodec.decode(data)
                    if (event != null) {
                        com.streamlink.shared.ai.TouchPerceptionHub.onRealTouch(event)
                        com.streamlink.app.control.RemoteControlAccessibilityService.instance?.handle(event)
                    }
                }
            }
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
                // Ensure we don't send empty metrics constantly
                if (state.fps > 0 || state.bitrateKbps > 0) {
                    val payload = org.json.JSONObject().apply {
                        put("fps", state.fps)
                        put("bitrateKbps", state.bitrateKbps)
                        // In a real app we'd get actual network loss and latency
                        put("packetLossPercent", 0) 
                        put("latencyMs", 35) // Hardcoded nominal latency for demonstration
                    }
                    signalingClient.sendMessage("METRICS", "broadcast", payload.toString())
                }
            }
        }

        // 3. Start MediaProjection Capture Service
        if (projectionData != null) {
            val serviceIntent = Intent(context, CaptureService::class.java).apply {
                action = CaptureService.ACTION_START
                putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(CaptureService.EXTRA_DATA, projectionData)
            }
            context.startForegroundService(serviceIntent)
        }

        scope.launch {
            GlobalStreamState.transition(GlobalStreamState.State.STREAM_STARTING)
            GlobalStreamState.transition(GlobalStreamState.State.STREAMING)
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
