package com.streamlink.wear.policy

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * FeaturePolicyEngine — Single authority for deciding whether optional features
 * (IMU gestures, Dynamic FPS) are active at any moment.
 *
 * The Phone sends *preferences*; the Watch enforces *policy* based on its own
 * real-time hardware conditions. This prevents the phone from inadvertently
 * enabling a feature that would overheat or drain the watch.
 *
 * Policy gates:
 *   IMU  = phonePref && isStreaming && isScreenOn && thermal < THERMAL_WARN && battery > BATTERY_MIN
 *   FPS  = effectiveFps = min(phonePrefFps, thermalThrottleFps)
 */
class FeaturePolicyEngine {

    companion object {
        private const val tag = "FeaturePolicyEngine"
        private const val THERMAL_WARN = 7     // 0-10 scale
        private const val BATTERY_MIN  = 15    // percent
    }

    // ── Phone preferences (arrive over control channel) ──────────────────────
    @Volatile var phonePrefDynamicFps: Boolean = false
        set(value) { field = value; evaluate() }

    @Volatile var phonePrefImuGestures: Boolean = false
        set(value) { field = value; evaluate() }

    // ── Watch hardware conditions ──────────────────────────────────────────────
    @Volatile var isStreaming: Boolean = false
        set(value) { field = value; evaluate() }

    @Volatile var isScreenOn: Boolean = true
        set(value) { field = value; evaluate() }

    @Volatile var thermalLevel: Int = 0           // 0-10
        set(value) { field = value; evaluate() }

    @Volatile var batteryPercent: Int = 100       // 0-100
        set(value) { field = value; evaluate() }

    // ── Effective decisions (observed by feature implementations) ─────────────
    private val _imuGesturesActive = MutableStateFlow(false)
    val imuGesturesActive: StateFlow<Boolean> = _imuGesturesActive.asStateFlow()

    private val _dynamicFpsActive = MutableStateFlow(false)
    val dynamicFpsActive: StateFlow<Boolean> = _dynamicFpsActive.asStateFlow()

    fun evaluate() {
        val canUseHardwareFeatures =
            isStreaming &&
            isScreenOn &&
            thermalLevel < THERMAL_WARN &&
            batteryPercent > BATTERY_MIN

        val newImu = phonePrefImuGestures && canUseHardwareFeatures
        val newFps = phonePrefDynamicFps && canUseHardwareFeatures

        if (_imuGesturesActive.value != newImu) {
            _imuGesturesActive.value = newImu
            Log.i(tag, "IMU policy changed → $newImu " +
                "(streaming=$isStreaming, screenOn=$isScreenOn, thermal=$thermalLevel, battery=$batteryPercent%)")
        }
        if (_dynamicFpsActive.value != newFps) {
            _dynamicFpsActive.value = newFps
            Log.i(tag, "DynamicFPS policy changed → $newFps " +
                "(streaming=$isStreaming, screenOn=$isScreenOn, thermal=$thermalLevel, battery=$batteryPercent%)")
        }
    }
}
