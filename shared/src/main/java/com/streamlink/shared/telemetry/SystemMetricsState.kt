package com.streamlink.shared.telemetry

import android.os.PowerManager

/**
 * Unified state vector for the Closed-Loop Control System.
 */
data class NetworkMetrics(
    val queueCongestion: Float = 0.0f, // 0.0 to 1.0 (1.0 = highly congested / dropping frames)
    val averageDelayMs: Float = 0.0f,
    val droppedFramesDelta: Long = 0L // Raw delta for instant keyframe reaction
)

data class SystemMetricsState(
    val network: NetworkMetrics = NetworkMetrics(),
    val thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
    val batteryLevel: Int = 100,
    val isCharging: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
