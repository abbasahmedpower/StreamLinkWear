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
    private val sessionController: StreamSessionController,
    private val unifiedQualityAuthority: com.streamlink.app.core.decision.UnifiedQualityAuthority
) {
    private val tag = "StreamingOrchestrator"
    private val settingsStore = com.streamlink.shared.util.SystemSettingsStore.get(context)
    private val settingsPrefs = SettingsPrefs.get(context)
    
    // --- Telemetry & Adaptive Engines ---
    private val telemetryRingBuffer = com.streamlink.app.core.telemetry.TelemetryRingBuffer()
    private val telemetryAggregator = com.streamlink.app.core.telemetry.TelemetryAggregator(telemetryRingBuffer)
    
    // Stage 5: Self-Healing
    private val recoveryManager = com.streamlink.app.core.StreamRecoveryManager(scope, this)

    // --- Session / Crypto (Phase D) ---
    @Volatile
    private var cryptoManager: com.streamlink.app.core.crypto.FastCryptoResumptionManager? = null
    
    // Active Channel Keys
    @Volatile
    private var videoCryptoContext: com.streamlink.app.core.crypto.ChannelCryptoContext? = null

    fun onPairingHandshakeComplete(realSessionKey: ByteArray) {
        cryptoManager = com.streamlink.app.core.crypto.FastCryptoResumptionManager(
            masterSecret = realSessionKey,
            sessionId = java.util.UUID.randomUUID().toString(),
            deviceNonce = "PhoneNonce"
        ).also {
            videoCryptoContext = it.deriveChannelKeys("video")
            android.util.Log.i(tag, "✅ Real ECDH SessionKey established & Crypto Contexts primed")
        }
    }

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
        
        // M-9: Wire negotiated key from server to the Orchestrator
        socketServer.onSessionEstablished = { key ->
            onPairingHandshakeComplete(key)
        }

        // Start Telemetry & Adaptive components
        telemetryAggregator.start(scope)
        
        // Start Self-Healing Worker
        recoveryManager.startListening()
        
        // Phase 1.5: JSON Control Protocol Sync (Replaces old callback/CMD approach)
        val settingsPrefs = com.streamlink.app.core.SettingsPrefs.get(context)
        scope.launch {
            kotlinx.coroutines.flow.combine(
                settingsStore.isDynamicFpsEnabled,
                settingsStore.isImuGesturesEnabled,
                settingsPrefs.bufferJitterMs,
                com.streamlink.shared.PairingManager.state
            ) { fps, imu, jitter, pairingState ->
                if (pairingState is com.streamlink.shared.PairingManager.PairingState.Paired) {
                    com.streamlink.shared.protocol.ControlMessage.SettingsUpdate(
                        dynamicFps = fps,
                        imuGestures = imu,
                        jitterBufferMs = jitter
                    )
                } else null
            }.collect { settingsUpdate ->
                settingsUpdate?.let {
                    socketServer.sendControlMessage(it)
                    android.util.Log.i(tag, "✅ Settings pushed to Watch via JSON ControlMessage")
                }
            }
        }

        // Wire Aggregator to UnifiedQualityAuthority (Phase 4)
        val cpuTracker = com.streamlink.shared.telemetry.ProcessCpuTracker()
        var snapshotCounter = 0L
        
        scope.launch {
            telemetryAggregator.aggregatedStats.collect { stats ->
                val latencyReport = latencyTracker.report()
                val rttMs = latencyReport.avgNetworkMs.toInt()
                val packetLossPercent = latencyReport.lateFramePct
                val jitterMs = latencyReport.jitterMs.toInt()
                val currentBitrateKbps = com.streamlink.shared.GlobalStreamState.snapshot.value.bitrateKbps
                val thermalLvl = thermalMonitor.thermalLevel.value

                // Metrics are already polled inside QualityController.intelEngine loop
                
                snapshotCounter++
                
                val snapshot = com.streamlink.app.core.decision.TelemetrySnapshot(
                    snapshotId = snapshotCounter,
                    rttMs = if (rttMs > 0) rttMs else 20, // default good
                    packetLossPercent = packetLossPercent,
                    jitterMs = jitterMs,
                    bitrateKbps = currentBitrateKbps,
                    thermalLevel = thermalLvl,
                    decoderDroppedFrames = stats.drops,
                    cpuLoad = cpuTracker.sampleFraction()
                )
                
                unifiedQualityAuthority.evaluate(snapshot)

                // Enforce Memory Budgets during streaming
                com.streamlink.app.core.telemetry.MemoryBudgetMonitor.checkBudgets()
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
                    unifiedQualityAuthority.adjustBitrateOnly(msg.value, "AI Reverse Control (Watch)")
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
        
        socketServer.onJsonControlMessage = { msg ->
            when (msg) {
                is com.streamlink.shared.protocol.ControlMessage.Ack -> {
                    Log.i(tag, "Received JSON ACK for messageId=${msg.messageId}, status=${msg.status}")
                    if (msg.status == "OK") {
                        settingsStore.setSyncState(com.streamlink.shared.util.SettingSyncState.CONFIRMED)
                    } else {
                        settingsStore.setSyncState(com.streamlink.shared.util.SettingSyncState.FAILED)
                    }
                }
                else -> {
                    Log.i(tag, "Received unknown JSON control message: $msg")
                }
            }
        }

        socketServer.onWatchDimensions = { w, h ->
            com.streamlink.app.control.RemoteControlAccessibilityService.instance
                ?.updateWatchDimensions(w, h)
        }
        
        socketServer.onClientConnected = { name, ip ->
            settingsStore.setConnectedWatch(name, ip)
            android.util.Log.i(tag, "Watch connected: $name ($ip)")
            
            // Force an instant KeyFrame to immediately start rendering on the watch (Zero-delay startup)
            hardwareEncoder.forceInstantKeyFrame()
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
        GlobalStreamState.transition(GlobalStreamState.State.AUTHENTICATING)
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
        val epoch = cryptoManager?.onNetworkHandover(transportType) ?: return false
        android.util.Log.i(tag, "Handover completed, new epoch: $epoch")
        
        // Refresh channel keys
        videoCryptoContext = cryptoManager?.deriveChannelKeys("video")
        
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
    fun lastKnownLocalHost(): String? = discovery.discoveredHost.value?.ip

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
