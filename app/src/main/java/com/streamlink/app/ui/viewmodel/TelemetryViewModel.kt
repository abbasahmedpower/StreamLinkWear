package com.streamlink.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamlink.app.core.StreamingOrchestrator
import com.streamlink.app.core.WearTelemetrySender
import com.streamlink.shared.telemetry.SystemMetricsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class TelemetryViewModel(
    private val orchestrator: StreamingOrchestrator,
    private val wearSender: WearTelemetrySender
) : ViewModel() {

    // ─── RAW flows (updated at hardware rate, up to 60Hz) ──────────────────────
    val metricsState: StateFlow<SystemMetricsState> = orchestrator.metricsCollector.metricsFlow

    // ─── THROTTLED flows for Text/Switch UI elements ───────────────────────────

    private val _currentBitrateRaw = MutableStateFlow(0)
    val currentBitrateRaw: StateFlow<Int> = _currentBitrateRaw.asStateFlow()

    val currentBitrateText: StateFlow<String> = _currentBitrateRaw
        .sample(250L) // 4 Hz throttle
        .map { kbps -> "$kbps Kbps" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "— Kbps")

    /**
     * Battery % label (sampled at 1 Hz).
     */
    val batteryText: StateFlow<String> = metricsState
        .sample(1_000L)
        .map { m -> "${m.batteryLevel}%" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "—%")

    /**
     * Queue congestion progress value (sampled at 500 ms for the progress bar).
     */
    val queueProgress: StateFlow<Float> = metricsState
        .sample(500L)
        .map { m -> m.network.queueCongestion }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    /**
     * Queue congestion label text (same throttle as progress bar).
     */
    val queueText: StateFlow<String> = metricsState
        .sample(500L)
        .map { m -> "${(m.network.queueCongestion * 100).toInt()}%" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "0%")

    // ─── AI optimizer switch ───────────────────────────────────────────────────
    private val _isOptimizerEnabled = MutableStateFlow(true)
    val isOptimizerEnabled: StateFlow<Boolean> = _isOptimizerEnabled.asStateFlow()

    // ─── Waveform history (ring buffer for the Canvas chart) ───────────────────
    private val WAVEFORM_CAPACITY = 60   // 60 data points ≈ ~15s at 250ms sampling
    private val _bitrateHistory = MutableStateFlow(FloatArray(WAVEFORM_CAPACITY))
    private var historyIndex = 0
    val bitrateHistory: StateFlow<FloatArray> = _bitrateHistory.asStateFlow()

    init {
        // Coroutine to calculate actual network throughput (bytes sent)
        viewModelScope.launch {
            var lastBytesSent = orchestrator.socketServer.bytesSent.get()
            while (true) {
                delay(250) // sample every 250ms
                
                val currentBytes = orchestrator.socketServer.bytesSent.get()
                val deltaBytes = currentBytes - lastBytesSent
                lastBytesSent = currentBytes
                
                // kbps = (bytes * 8 bits/byte) / 1000 bits/kbit * (4 samples/sec)
                val kbps = ((deltaBytes * 8 * 4) / 1000).toInt()
                
                _currentBitrateRaw.value = kbps

                // Update ring-buffer waveform history
                val arr = _bitrateHistory.value.copyOf()
                arr[historyIndex % WAVEFORM_CAPACITY] = kbps.toFloat()
                historyIndex++
                _bitrateHistory.value = arr

                // Broadcast latest snapshot to the paired Wear OS watch
                wearSender.sendLatestTelemetry(
                    metrics = metricsState.value,
                    currentBitrate = kbps
                )
            }
        }
    }

    fun toggleOptimizer(enabled: Boolean) {
        _isOptimizerEnabled.value = enabled
        orchestrator.isFuzzyOptimizationEnabled = enabled
    }

    fun startCasting() {
        viewModelScope.launch {
            orchestrator.startStream(
                url = "",
                resultCode = 0,
                projectionData = null,
                isDrm = false,
                networkQuality = 1.0f
            )
        }
    }

    fun stopCasting() {
        viewModelScope.launch {
            orchestrator.stopStream()
        }
    }
}
