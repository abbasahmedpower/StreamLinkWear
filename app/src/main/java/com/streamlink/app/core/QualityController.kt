package com.streamlink.app.core

import android.content.Context
import android.util.Log
import com.streamlink.app.capture.HardwareEncoder
import com.streamlink.app.telemetry.HardwareActuator
import com.streamlink.shared.GlobalStreamState
import com.streamlink.shared.ResolutionProfile
import com.streamlink.shared.StreamProtocol
import com.streamlink.shared.StreamingIntelligenceEngine
import com.streamlink.shared.ThermalMonitor
import com.streamlink.shared.telemetry.FuzzyDecisionEngine
import com.streamlink.shared.telemetry.MetricsCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * QualityController — owns ABR and quality decision engines:
 * - FuzzyDecisionEngine
 * - StreamingIntelligenceEngine
 * - SettingsPrefs quality/jitter observers
 * - Battery-aware throttling
 */
@Singleton
class QualityController @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val scope: CoroutineScope,
    private val hardwareEncoder: HardwareEncoder,
    private val thermalMonitor: ThermalMonitor,
    private val networkController: NetworkController,
    private val latencyTracker: com.streamlink.shared.LatencyTracker,
    val metricsCollector: MetricsCollector,
    val fuzzyDecisionEngine: FuzzyDecisionEngine
) {
    private val tag = "QualityController"

    private var currentWidth = StreamProtocol.WEAR_W_FULL
    private var currentHeight = StreamProtocol.WEAR_H_FULL
    private var currentFps = StreamProtocol.WEAR_FPS_FULL
    private var currentBitrate = StreamProtocol.WEAR_BPS_FULL

    private val hardwareActuator = HardwareActuator(hardwareEncoder)

    @Volatile var isFuzzyOptimizationEnabled = true

    val intelEngine = StreamingIntelligenceEngine(
        scope = scope,
        onBitrateChange = { kbps ->
            currentBitrate = kbps
            hardwareEncoder.setBitrate(kbps)
        },
        onFpsChange = { fps ->
            currentFps = fps
            hardwareEncoder.reconfigure(ResolutionProfile("CUSTOM", currentWidth, currentHeight, currentFps, currentBitrate))
        },
        onResolutionChange = { scale ->
            currentWidth = (StreamProtocol.WEAR_W_FULL * scale).toInt()
            currentHeight = (StreamProtocol.WEAR_H_FULL * scale).toInt()
            hardwareEncoder.reconfigure(ResolutionProfile("CUSTOM", currentWidth, currentHeight, currentFps, currentBitrate))
        },
        onIFrameIntervalChange = { _ ->
            hardwareEncoder.forceKeyframe()
        }
    )

    fun startWiring() {
        // Wire Fuzzy Engine to Hardware Actuator
        fuzzyDecisionEngine.controlActionsFlow
            .onEach { action ->
                if (isFuzzyOptimizationEnabled) {
                    hardwareActuator.applyControlAction(action)
                }
            }
            .launchIn(scope)

        // Poll DirectSocketServer metrics every 1 second
        scope.launch {
            while (true) {
                metricsCollector.updateTcpStats(
                    queueDepth = 0,
                    totalDroppedFrames = 0,
                    averageDelayMs = 0f
                )
                delay(1000)
            }
        }

        // Live quality/bitrate adjustments from SettingsPrefs
        scope.launch {
            SettingsPrefs.get(context).quality.collect { quality ->
                val newBitrate = quality.targetBitrateKbps
                val newFps = quality.targetFps
                if (currentBitrate != newBitrate || currentFps != newFps) {
                    currentBitrate = newBitrate
                    currentFps = newFps
                    hardwareEncoder.reconfigure(
                        ResolutionProfile(quality.name, currentWidth, currentHeight, currentFps, currentBitrate)
                    )
                    Log.i(tag, "Quality changed to $quality → $newBitrate kbps @ $newFps fps")
                }
            }
        }

        // ✅ NANO-FIX: حُذف الـ collect المباشر على bufferJitterMs من هنا.
        // المسار الصحيح الوحيد الآن:
        //   setBufferJitterMs() → onJitterBufferSendRequested → StreamingOrchestrator → socketServer
        // وده بيحترم isInstantSyncEnabled تلقائياً.
        // نسيب بس إعادة الإرسال عند إعادة الاتصال (Paired) عشان الساعة تاخد الإعداد الحالي.

        // Resend jitter setting on new pairing — ensures watch always has current value after reconnect
        scope.launch {
            com.streamlink.shared.PairingManager.state.collect { state ->
                if (state is com.streamlink.shared.PairingManager.PairingState.Paired) {
                    val ms = SettingsPrefs.get(context).bufferJitterMs.value
                    networkController.sendControlToWatch(StreamProtocol.CMD_SET_BUFFER_JITTER_MS, ms)
                }
            }
        }

        // IntelEngine metrics polling — only during active streaming
        scope.launch {
            GlobalStreamState.snapshot.collect { snapshot ->
                if (snapshot.state == GlobalStreamState.State.STREAMING ||
                    snapshot.state == GlobalStreamState.State.DEGRADED) {
                    val report = latencyTracker.report()
                    intelEngine.currentRttMs = report.avgE2EMs
                    intelEngine.jitterMs = report.jitterMs
                    intelEngine.packetLossRate = report.lateFramePct / 100f
                    intelEngine.currentFps = currentFps
                    intelEngine.thermalLevel = thermalMonitor.thermalLevel.value
                    val bm = runCatching {
                        context.getSystemService(android.content.Context.BATTERY_SERVICE)
                            as? android.os.BatteryManager
                    }.getOrNull()
                    intelEngine.batteryLevel = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
                    intelEngine.cpuUsagePercent = 10f
                }
            }
        }
    }

    /** Feed metrics from the socket server so quality decisions stay accurate. */
    fun updateSocketMetrics(queueDepth: Int, droppedFrames: Long, averageDelayMs: Float) {
        scope.launch {
            metricsCollector.updateTcpStats(queueDepth, droppedFrames, averageDelayMs)
        }
    }
}
