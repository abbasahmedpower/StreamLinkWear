package com.streamlink.wear.ai

import android.util.Log
import com.streamlink.shared.GlobalStreamState

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

class SmartWatchUXEngine(
    private val onDynamicParametersChanged: (bitrate: Int, fps: Int) -> Unit
) {
    private val tag = "SmartWatchUXEngine"
    private val _uxMode = MutableStateFlow(UXOptimizationMode.MAX_PERFORMANCE)
    val uxMode: StateFlow<UXOptimizationMode> = _uxMode

    /**
     * The TFLite model instance. Remains null until [loadTfliteModel] is called.
     * Guarded by a null-check to guarantee safe behaviour even if the model was
     * never loaded or failed to load.
     */
    var tfliteModel: TfliteGestureModel? = null

    fun processWristMetrics(rotationX: Float, accelerationZ: Float) {
        if (GlobalStreamState.current != GlobalStreamState.State.STREAMING) return

        // ✅ N1 FIX: Guard on BOTH the feature flag AND model availability.
        // If the flag is off OR the model wasn't loaded yet, always fall through
        // to the heuristic — never throw NotImplementedError.
        if (!AiFeatureFlag.PREDICTION_ENABLED || tfliteModel == null) {
            applyHeuristic(rotationX, accelerationZ)
            return
        }

        // ✅ N1 FIX: Wrap TFLite inference in try/catch.
        // Inference errors (OOM, corrupted model, API mis-match) must NOT crash the watch UI.
        // They must degrade gracefully back to the heuristic and leave a log trail.
        try {
            val model = tfliteModel ?: return          // redundant but makes smart-cast happy
            val (targetBitrate, targetFps) = model.predict(rotationX, accelerationZ)
            val newMode = if (targetFps <= 10) UXOptimizationMode.BATTERY_SAVER
                          else UXOptimizationMode.MAX_PERFORMANCE
            if (_uxMode.value != newMode) {
                _uxMode.value = newMode
                onDynamicParametersChanged(targetBitrate, targetFps)
            }
        } catch (e: Exception) {
            Log.w(tag, "TFLite inference failed — degrading to heuristic: ${e.message}")
            applyHeuristic(rotationX, accelerationZ)
        }
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

/**
 * Stub interface for the TFLite gesture model.
 * Replace with your concrete implementation after export_model.py completes.
 * Contract: returns (targetBitrateKbps, targetFps).
 */
interface TfliteGestureModel {
    fun predict(rotationX: Float, accelerationZ: Float): Pair<Int, Int>
}

enum class UXOptimizationMode {
    MAX_PERFORMANCE, BATTERY_SAVER
}
