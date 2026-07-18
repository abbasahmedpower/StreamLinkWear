package com.streamlink.app.core

import android.content.Context
import android.content.Intent
import android.util.Log
import com.streamlink.app.capture.CaptureService
import com.streamlink.app.capture.HardwareEncoder
import com.streamlink.app.stream.MirrorDataPlane
import com.streamlink.shared.EventPipeline
import com.streamlink.shared.GlobalStreamState
import com.streamlink.shared.StreamProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamSessionController @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val scope: CoroutineScope,
    private val events: EventPipeline,
    private val networkController: NetworkController,
    private val qualityController: QualityController,
    private val touchPipeline: TouchPipeline,
    private val telemetryCollector: TelemetryCollector,
    private val mirrorDataPlane: MirrorDataPlane,
    private val hardwareEncoder: HardwareEncoder,
    private val connectionManager: com.streamlink.shared.ConnectionManager
) {
    private val tag = "StreamSessionController"

    private var blackoutManager: com.streamlink.app.core.overlay.PrivacyBlackoutOverlayManager? = null
    private val settingsStore = com.streamlink.shared.util.SystemSettingsStore(context)
    private var batteryReceiver: android.content.BroadcastReceiver? = null

    fun initialize() {
        // Auto-reconnect triggered by ConnectionManager
        connectionManager.watchState(scope) {
            Log.i(tag, "🔄 Auto-reconnect triggered by ConnectionManager")
            scope.launch { 
                networkController.startTcpServer()
            }
            mirrorDataPlane.start(scope)
        }
        touchPipeline.startRealtimeConsumer()
        qualityController.startWiring()
        telemetryCollector.startCollection()
    }

    private fun startBatteryListener() {
        if (batteryReceiver != null) return
        batteryReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level != -1 && scale != -1) {
                    val pct = (level * 100) / scale
                    if (pct <= 20) {
                        hardwareEncoder.setThermalThrottled(true)
                        Log.i(tag, "Battery <= 20%, applying thermal/battery throttle")
                    } else {
                        hardwareEncoder.setThermalThrottled(false)
                    }
                }
            }
        }
        context.registerReceiver(
            batteryReceiver, 
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        )
    }

    private fun stopBatteryListener() {
        batteryReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(tag, "Failed to unregister battery receiver", e)
            }
        }
        batteryReceiver = null
    }

    fun startStream(
        url: String,
        resultCode: Int,
        projectionData: Intent?,
        isDrm: Boolean,
        networkQuality: Float
    ) {
        Log.i(tag, "Starting stream ✨ url=$url drm=$isDrm nq=$networkQuality")
        
        if (settingsStore.isPrivacyBlackoutEnabled) {
            blackoutManager = com.streamlink.app.core.overlay.PrivacyBlackoutOverlayManager(context).apply {
                enable()
            }
        }

        scope.launch {
            GlobalStreamState.transition(GlobalStreamState.State.CONNECTING)
        }
        events.sessionStart(java.util.UUID.randomUUID().toString(), StreamProtocol.MODE_MIRROR)

        // 1. Start Servers & Routing
        networkController.startServers()

        // 2. Start Data Plane
        mirrorDataPlane.start(scope)
        startBatteryListener()

        qualityController.intelEngine.start()
        hardwareEncoder.resume()

        // 3. Start MediaProjection Capture Service
        if (projectionData != null) {
            scope.launch {
                val serverReady = withTimeoutOrNull(3_000L) {
                    var attempts = 0
                    while (!networkController.isServerRunning && attempts < 30) {
                        kotlinx.coroutines.delay(100)
                        attempts++
                    }
                    networkController.isServerRunning
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

    fun stopStream() {
        Log.i(tag, "Stopping stream")
        stopBatteryListener()
        blackoutManager?.disable()
        blackoutManager = null
        
        qualityController.intelEngine.stop()
        hardwareEncoder.pause()
        com.streamlink.shared.ai.TouchPerceptionHub.reset()
        val stopIntent = Intent(context, CaptureService::class.java).apply {
            action = CaptureService.ACTION_STOP
        }
        try {
            context.startService(stopIntent)
        } catch (e: Exception) {
            Log.w(tag, "Failed to send stop intent to CaptureService", e)
        }
        try {
            val svcIntent = Intent(context, CaptureService::class.java)
            context.stopService(svcIntent)
        } catch (e: Exception) {
            Log.w(tag, "Failed to stop CaptureService via stopService()", e)
        }
        mirrorDataPlane.stop()
        networkController.stopServers()
        touchPipeline.stop()
        
        scope.launch {
            GlobalStreamState.transition(GlobalStreamState.State.STOPPED)
        }
    }
}
