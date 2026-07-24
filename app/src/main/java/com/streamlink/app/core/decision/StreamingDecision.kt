package com.streamlink.app.core.decision

// 1. Input: Comprehensive snapshot from all Telemetry Modules
data class TelemetrySnapshot(
    val snapshotId: Long = 0L,
    val rttMs: Int = 0,
    val packetLossPercent: Float = 0f,
    val jitterMs: Int = 0,
    val bitrateKbps: Int = 0,
    /** thermalCelsius is DEPRECATED — تُستخدم thermalLevel (0-10) من ThermalMonitor مباشرة */
    @Deprecated("Use thermalLevel instead")
    val thermalCelsius: Float = 30f,
    /** thermalLevel: 0 (NONE) → 10 (EMERGENCY/SHUTDOWN) من ThermalMonitor.mapStatusToLevel() */
    val thermalLevel: Int = 0,
    val batteryPercent: Int = 100,
    val decoderDroppedFrames: Int = 0,
    /** cpuLoad: 0f..1f من ProcessCpuTracker — امسح أي قيمة ثابتة (0.5f, 10f) */
    val cpuLoad: Float = 0f
)

// 2. Output: The architectural decision for the Adaptive Engine to execute
enum class StreamingProfile(val targetFps: Int, val bitrateMultiplier: Float) {
    ULTRA(60, 1.0f),      // Perfect conditions
    HIGH(60, 0.75f),      // Minor network jitter
    BALANCED(30, 0.50f),  // Thermal rising or moderate packet loss
    SURVIVAL(24, 0.25f)   // Critical state (Prevent crash/disconnect)
}

data class StreamingDecision(
    val targetProfile: StreamingProfile,
    val healthScore: Float,
    val immediateActionRequired: Boolean
)
