package com.streamlink.shared.telemetry

import android.os.PowerManager
import android.util.Log

/**
 * MICRO-05: Thermal Governor Policies
 * Defines explicit fallback steps based on thermal throttling.
 * Tailored for Wear OS physical dimensions (e.g., base ~454x454).
 */
object ThermalGovernor {

    private const val TAG = "ThermalGovernor"

    data class ThermalPolicy(
        val maxFps: Int,
        val maxBitrateKbps: Int,
        val resolutionScale: Float,
        val isPaused: Boolean = false
    )

    fun getPolicyForStatus(thermalStatus: Int): ThermalPolicy {
        return when (thermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> {
                // Level 0: No restrictions
                ThermalPolicy(maxFps = 60, maxBitrateKbps = 4000, resolutionScale = 1.0f)
            }
            PowerManager.THERMAL_STATUS_LIGHT -> {
                // Level 1: Drop FPS to reduce rendering/encoding load slightly
                ThermalPolicy(maxFps = 30, maxBitrateKbps = 2500, resolutionScale = 1.0f)
            }
            PowerManager.THERMAL_STATUS_MODERATE -> {
                // Level 2: Drop Bitrate heavily to reduce network radio transmission heat
                ThermalPolicy(maxFps = 30, maxBitrateKbps = 1200, resolutionScale = 1.0f)
            }
            PowerManager.THERMAL_STATUS_SEVERE -> {
                // Level 3: Drop Resolution and FPS dramatically (WearOS targeted: 0.5 scale = ~227x227)
                ThermalPolicy(maxFps = 15, maxBitrateKbps = 800, resolutionScale = 0.5f)
            }
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> {
                // Level 4: Thermal Emergency. Pause streaming to save the device.
                ThermalPolicy(maxFps = 1, maxBitrateKbps = 100, resolutionScale = 0.25f, isPaused = true)
            }
            else -> {
                ThermalPolicy(maxFps = 60, maxBitrateKbps = 4000, resolutionScale = 1.0f)
            }
        }
    }

    fun applyPolicy(status: Int) {
        val policy = getPolicyForStatus(status)
        Log.w(TAG, "Applying Thermal Policy (Status $status): FPS=${policy.maxFps}, Bitrate=${policy.maxBitrateKbps}, Scale=${policy.resolutionScale}, Paused=${policy.isPaused}")
        // StreamingOrchestrator will observe this and throttle HardwareEncoder
    }
}
