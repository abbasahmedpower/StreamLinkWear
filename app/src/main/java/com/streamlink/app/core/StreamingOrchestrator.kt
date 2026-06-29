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
    private val mirrorDataPlane: MirrorDataPlane,
    private val hardwareEncoder: HardwareEncoder
) {
    private val tag = "StreamingOrchestrator"
    
    init {
        socketServer.onTouchEvent = { event ->
            com.streamlink.shared.ai.TouchPerceptionHub.onRealTouch(event)
            com.streamlink.app.control.RemoteControlAccessibilityService.instance?.handle(event)
        }
    }

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
        webRtcTransport?.initialize()

        // 2. Start Data Plane
        mirrorDataPlane.start(scope)

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
        GlobalStreamState.transition(GlobalStreamState.State.STOPPED)
    }

    fun requestKeyframe() {
        Log.i(tag, "Keyframe requested")
        hardwareEncoder.forceKeyframe()
    }
}
