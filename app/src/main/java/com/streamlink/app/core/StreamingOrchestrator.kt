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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
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
    private val settingsStore = com.streamlink.shared.util.SystemSettingsStore.get(context)
    private val settingsPrefs = SettingsPrefs.get(context)
    
    // --- Telemetry & Adaptive Engines ---
    private val telemetryRingBuffer = com.streamlink.app.core.telemetry.TelemetryRingBuffer()
    private val telemetryAggregator = com.streamlink.app.core.telemetry.TelemetryAggregator(telemetryRingBuffer)
    private val decisionEngine = com.streamlink.app.core.decision.DecisionEngine()
    private val adaptiveEngine = com.streamlink.app.core.adaptive.AdaptiveQualityEngine(
        hardwareEncoder = hardwareEncoder,
        baseBitrateBps = StreamProtocol.WEAR_BPS_FULL * 1000,
        decisionFlow = decisionEngine.decisionFlow
    )

    // --- Session / Crypto (Phase D) ---
    private val cryptoManager = com.streamlink.app.core.crypto.FastCryptoResumptionManager(
        masterSecret = ByteArray(32) { 1 }, // TODO: Inject actual ECDH negotiated secret
        sessionId = java.util.UUID.randomUUID().toString(),
        deviceNonce = "PhoneNonce"
    )
    
    // Active Channel Keys
    @Volatile
    private var videoCryptoContext = cryptoManager.deriveChannelKeys("video")

    private var heartbeatJob: kotlinx.coroutines.Job? = null
    private val wearMessageClient by lazy { com.google.android.gms.wearable.Wearable.getMessageClient(context) }
    private val wearNodeClient by lazy { com.google.android.gms.wearable.Wearable.getNodeClient(context) }

    fun startBluetoothHeartbeat() {
        stopBluetoothHeartbeat() // Ensure no duplicate jobs
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val nodes = wearNodeClient.connectedNodes.await()
                    nodes.forEach { node ->
                        wearMessageClient.sendMessage(
                            node.id,
                            StreamProtocol.PATH_HEARTBEAT_PING,
                            byteArrayOf()
                        )
                    }
                    Log.d(tag, "BT Control Heartbeat sent to Watch")
                } catch (e: Exception) {
                    Log.w(tag, "Failed to send BT heartbeat: ${e.message}")
                }
                delay(3000L) // Ping every 3 seconds
            }
        }
    }

    fun stopBluetoothHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // ─── Public delegates for backward compatibility ─────────────────────────

    val metricsCollector: MetricsCollector get() = qualityController.metricsCollector
    val fuzzyDecisionEngine: FuzzyDecisionEngine get() = qualityController.fuzzyDecisionEngine

    var isFuzzyOptimizationEnabled: Boolean
        get() = qualityController.isFuzzyOptimizationEnabled
        set(value) { qualityController.isFuzzyOptimizationEnabled = value }

    // ─── Initialization ──────────────────────────────────────────────────────

    init {
        // Wire HardwareEncoder to TelemetryRingBuffer
        hardwareEncoder.telemetryRingBuffer = telemetryRingBuffer

        // Start Telemetry & Adaptive components
        telemetryAggregator.start(scope)
        adaptiveEngine.startListening(scope)

        // Wire Aggregator to Decision Engine
        scope.launch {
            telemetryAggregator.aggregatedStats.collect { stats ->
                val rttMs = latencyTracker.report().avgNetworkMs.toInt()
                val thermalCelsius = thermalMonitor.thermalLevel.value.toFloat() * 10f // mock mapping
                
                val snapshot = com.streamlink.app.core.decision.TelemetrySnapshot(
                    rttMs = if (rttMs > 0) rttMs else 20, // default good
                    thermalCelsius = if (thermalCelsius > 0) thermalCelsius else 35f, // default normal
                    packetLossPercent = 0f, // TODO: Hook into network layer packet loss
                    decoderDroppedFrames = stats.drops
                )
                decisionEngine.evaluate(snapshot)
            }
        }

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
                StreamProtocol.CMD_REQUEST_KEYFRAME -> {
                    Log.i(tag, "Watch requested IDR frame (Surface recovery/Ambient exit)")
                    requestKeyframe()
                }
                StreamProtocol.CMD_EPOCH_ACK -> {
                    val epoch = msg.value
                    Log.i(tag, "Received Epoch ACK from Watch: $epoch")
                    // If Watch ACK matches our current epoch, we can safely resume data transmission.
                    // In a full implementation, we'd unblock the MirrorDataPlane queue here.
                }
            }
        }
        socketServer.onWatchDimensions = { w, h ->
            com.streamlink.app.control.RemoteControlAccessibilityService.instance
                ?.updateWatchDimensions(w, h)
        }
        
        socketServer.onClientConnected = { name, ip ->
            settingsStore.setConnectedWatch(name, ip)
            // ✅ NANO-FIX: sync أساسي يحصل دايمًا عند أول اتصال — مش مرتبط بـ Instant Sync.
            // الساعة دايمًا تبدأ بالقيم المحفوظة عند المستخدم مش بالقيم الهارد-كودد الافتراضية.
            val currentJitter = settingsPrefs.bufferJitterMs.value
            socketServer.sendControlToWatch(StreamProtocol.CMD_SET_BUFFER_JITTER_MS, currentJitter)
            Log.i(tag, "Watch connected: $name ($ip) — pushed jitter=${currentJitter}ms")
            
            // Force an instant KeyFrame to immediately start rendering on the watch (Zero-delay startup)
            hardwareEncoder.forceInstantKeyFrame()
        }

        // Instant Sync: يرسل فوراً التحديثات اللحظية *أثناء* الاتصال فقط
        settingsPrefs.onJitterBufferSendRequested = { ms ->
            val isStreaming = com.streamlink.shared.GlobalStreamState.snapshot.value.state ==
                com.streamlink.shared.GlobalStreamState.State.STREAMING
            if (isStreaming && settingsStore.isInstantSyncEnabled.value) {
                socketServer.sendControlToWatch(StreamProtocol.CMD_SET_BUFFER_JITTER_MS, ms)
                Log.i(tag, "✅ Jitter Buffer → Watch: ${ms}ms (InstantSync active)")
            }
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
    
    fun pauseVideo() = mirrorDataPlane.pauseVideoFrameDelivery()
    
    fun resumeVideo() = mirrorDataPlane.resumeVideoFrameDelivery()
    
    fun triggerInstantSync() {
        val currentJitter = settingsPrefs.bufferJitterMs.value
        socketServer.sendControlToWatch(StreamProtocol.CMD_SET_BUFFER_JITTER_MS, currentJitter)
        Log.i(tag, "Instant Sync sent: Jitter=${currentJitter}ms")
    }

    suspend fun migrateTransportSocket(newHost: String, newPort: Int, isRelay: Boolean, transportType: String = "WIFI"): Boolean {
        // Zero-RTT Crypto Resumption
        val epoch = cryptoManager.onNetworkHandover(transportType)
        
        // Immediately re-derive all channel keys securely
        videoCryptoContext = cryptoManager.deriveChannelKeys("video")
        
        // Perform Epoch ACK Handshake
        // We send the ACK to the watch. The watch must derive the same keys and ACK back.
        socketServer.sendControlToWatch(StreamProtocol.CMD_EPOCH_ACK, epoch)
        Log.i(tag, "Crypto Epoch $epoch Handshake initiated. Migrating socket...")

        return networkController.migrateTransportSocket(newHost, newPort, isRelay)
    }

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
