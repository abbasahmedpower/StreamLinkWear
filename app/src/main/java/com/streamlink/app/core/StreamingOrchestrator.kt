package com.streamlink.app.core

import android.content.Context
import android.content.Intent
import android.util.Log
import com.streamlink.shared.DirectSocketServer
import com.streamlink.shared.EventPipeline
import com.streamlink.shared.GlobalStreamState
import com.streamlink.shared.LatencyTracker
import com.streamlink.shared.StreamProtocol
import com.streamlink.shared.ThermalMonitor
import com.streamlink.shared.ConnectionManager
import com.streamlink.shared.NetworkDiscovery
import com.streamlink.shared.StreamRouter
import com.streamlink.shared.telemetry.FuzzyDecisionEngine
import com.streamlink.shared.telemetry.MetricsCollector
import com.streamlink.app.capture.CaptureService
import com.streamlink.app.capture.HardwareEncoder
import com.streamlink.app.stream.MirrorDataPlane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * StreamingOrchestrator — Facade (Phase 4 Refactored).
 *
 * This class now acts as a thin coordinator that wires together the five
 * dedicated sub-components. The public API is intentionally unchanged to
 * preserve compatibility with MainActivity, CaptureService, and HandoverCoordinator.
 *
 * Sub-components:
 *   - [StreamSessionController]  → start/stop lifecycle, MediaProjection
 *   - [NetworkController]        → TCP server, WebRTC transport, socket migration
 *   - [QualityController]        → ABR, FuzzyEngine, IntelEngine, SettingsPrefs
 *   - [TouchPipeline]            → lock-free ring buffer for real-time touch
 *   - [TelemetryCollector]       → metrics → backend signaling
 */
@Singleton
class StreamingOrchestrator @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val scope: CoroutineScope,
    private val events: EventPipeline,
    val socketServer: DirectSocketServer,
    private val streamRouter: StreamRouter,
    private val mirrorDataPlane: MirrorDataPlane,
    private val hardwareEncoder: HardwareEncoder,
    private val latencyTracker: LatencyTracker,
    private val thermalMonitor: ThermalMonitor,
    private val connectionManager: ConnectionManager,
    private val discovery: NetworkDiscovery,
    // Sub-components (Hilt-injected)
    private val networkController: NetworkController,
    private val qualityController: QualityController,
    private val touchPipeline: TouchPipeline,
    private val telemetryCollector: TelemetryCollector,
    private val sessionController: StreamSessionController
) {
    private val tag = "StreamingOrchestrator"

    // ─── Public delegates for backward compatibility ─────────────────────────

    val metricsCollector: MetricsCollector get() = qualityController.metricsCollector
    val fuzzyDecisionEngine: FuzzyDecisionEngine get() = qualityController.fuzzyDecisionEngine

    var isFuzzyOptimizationEnabled: Boolean
        get() = qualityController.isFuzzyOptimizationEnabled
        set(value) { qualityController.isFuzzyOptimizationEnabled = value }

    // ─── Initialization ──────────────────────────────────────────────────────

    init {
        // Wire touch events from socket server to the touch pipeline
        socketServer.onTouchEvent = { event ->
            touchPipeline.publishTouch(
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
                    Log.i(tag, "AI Reverse Control: Bitrate → ${msg.value} kbps")
                    hardwareEncoder.setBitrate(msg.value)
                }
                StreamProtocol.CMD_SET_QUALITY_MODE -> {
                    val newMode = com.streamlink.shared.QualityMode.values().getOrNull(msg.value)
                        ?: com.streamlink.shared.QualityMode.BALANCED
                    Log.i(tag, "Received QUALITY_MODE from watch: $newMode")
                    SettingsPrefs.get(context).setQuality(newMode)
                }
                StreamProtocol.CMD_GLOBAL_ACTION -> {
                    Log.i(tag, "Remote global action: ${msg.value}")
                    com.streamlink.app.control.RemoteControlAccessibilityService.instance
                        ?.performGlobalAction(msg.value)
                }
            }
        }
        socketServer.onWatchDimensions = { w, h ->
            com.streamlink.app.control.RemoteControlAccessibilityService.instance
                ?.updateWatchDimensions(w, h)
        }

        // Socket metrics → QualityController (runs every 1s in quality wiring)
        // Replaced by direct polling inside QualityController.startWiring()

        // Boot sub-components
        sessionController.initialize()
    }

    // ─── Public API (unchanged interface) ────────────────────────────────────

    fun startStream(
        context: Context,
        url: String,
        resultCode: Int,
        projectionData: Intent?,
        isDrm: Boolean,
        networkQuality: Float
    ) = sessionController.startStream(url, resultCode, projectionData, isDrm, networkQuality)

    /** Suspend overload for coroutine callers. */
    suspend fun startStream(
        url: String,
        resultCode: Int,
        projectionData: Intent?,
        isDrm: Boolean,
        networkQuality: Float
    ) {
        Log.i(tag, "startStream (suspend) → url=$url")
        GlobalStreamState.transition(GlobalStreamState.State.CONNECTING)
        events.sessionStart(java.util.UUID.randomUUID().toString(), StreamProtocol.MODE_MIRROR)
        networkController.startTcpServer()
        qualityController.intelEngine.start()
        hardwareEncoder.resume()
        mirrorDataPlane.start(scope)
        GlobalStreamState.transition(GlobalStreamState.State.STREAM_STARTING)
        GlobalStreamState.transition(GlobalStreamState.State.STREAMING)
    }

    fun stopStream(context: Context) = sessionController.stopStream()
    fun stopStream() = sessionController.stopStream()

    fun requestKeyframe() {
        Log.i(tag, "Keyframe requested")
        hardwareEncoder.forceKeyframe()
    }

    fun pauseTransport() = networkController.pauseTransport()

    suspend fun migrateTransportSocket(newHost: String, newPort: Int, isRelay: Boolean): Boolean =
        networkController.migrateTransportSocket(newHost, newPort, isRelay)

    /**
     * NANO-FIX (HandoverCoordinator hardcoded-IP bug): exposes the last host actually
     * discovered via mDNS/NSD, so callers stop guessing a fake LAN address.
     * Returns null if no watch has been discovered yet on this network.
     */
    fun lastKnownLocalHost(): String? = discovery.discoveredHost.value

    /** Surfaces a user-facing, non-crashing reason on the stream UI + event log. */
    fun reportTransportIssue(code: String, message: String) {
        events.error(code, message, recoverable = true)
        scope.launch {
            GlobalStreamState.update { copy(errorMessage = message) }
        }
    }

    /** Convenience for callers that still reference publishTouch directly. */
    fun publishTouch(phase: Byte, pointerId: Int, nx: Float, ny: Float, timestampUs: Long) =
        touchPipeline.publishTouch(phase, pointerId, nx, ny, timestampUs)
}
