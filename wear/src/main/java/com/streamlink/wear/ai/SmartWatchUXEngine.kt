package com.streamlink.wear.ai

import com.streamlink.shared.GlobalStreamState

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

class SmartWatchUXEngine(
    private val onDynamicParametersChanged: (bitrate: Int, fps: Int) -> Unit
) {
    private val _uxMode = MutableStateFlow(UXOptimizationMode.MAX_PERFORMANCE)
    val uxMode: StateFlow<UXOptimizationMode> = _uxMode

    fun processWristMetrics(rotationX: Float, accelerationZ: Float) {
        if (GlobalStreamState.current != GlobalStreamState.State.STREAMING) return

        if (!AiFeatureFlag.PREDICTION_ENABLED) {
            // Fallback to heuristic controller
            applyHeuristic(rotationX, accelerationZ)
            return
        }

        TODO("Wire real .tflite model after export_model.py is complete")
    }

    private fun applyHeuristic(rotationX: Float, accelerationZ: Float) {

        if (abs(rotationX) > 1.2f || accelerationZ < -4.0f) {
            if (_uxMode.value != UXOptimizationMode.BATTERY_SAVER) {
                _uxMode.value = UXOptimizationMode.BATTERY_SAVER
                onDynamicParametersChanged(150_000, 10) // 150kbps, 10 FPS
            }
        } else {
            if (_uxMode.value != UXOptimizationMode.MAX_PERFORMANCE) {
                _uxMode.value = UXOptimizationMode.MAX_PERFORMANCE
                onDynamicParametersChanged(1_200_000, 30) // 1.2Mbps, 30 FPS
            }
        }
    }
}

enum class UXOptimizationMode {
    MAX_PERFORMANCE, BATTERY_SAVER
}
