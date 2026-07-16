package com.streamlink.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamlink.app.core.StreamingOrchestrator
import com.streamlink.app.core.WearTelemetrySender
import com.streamlink.shared.telemetry.SystemMetricsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TelemetryViewModel(
    private val orchestrator: StreamingOrchestrator,
    private val wearSender: WearTelemetrySender
) : ViewModel() {

    // 1. مراقبة حالة المستشعرات الحية (Battery, Thermal, Network Queue)
    val metricsState: StateFlow<SystemMetricsState> = orchestrator.metricsCollector.metricsFlow

    // 2. مراقبة قيمة الـ Bitrate الحالية التي يقررها الـ Fuzzy Engine ويطبقها الـ Actuator
    private val _currentBitrate = MutableStateFlow(1500)
    val currentBitrate: StateFlow<Int> = _currentBitrate.asStateFlow()

    // 3. التحكم في تشغيل وإيقاف الـ AI Bitrate Optimizer من الـ Switch في الواجهة
    private val _isOptimizerEnabled = MutableStateFlow(true)
    val isOptimizerEnabled: StateFlow<Boolean> = _isOptimizerEnabled.asStateFlow()

    init {
        viewModelScope.launch {
            orchestrator.fuzzyDecisionEngine.controlActionsFlow.collect { action ->
                _currentBitrate.value = action.targetBitrateKbps
                // Broadcast the latest snapshot to the paired Wear OS watch
                wearSender.sendLatestTelemetry(
                    metrics = metricsState.value,
                    currentBitrate = action.targetBitrateKbps
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
