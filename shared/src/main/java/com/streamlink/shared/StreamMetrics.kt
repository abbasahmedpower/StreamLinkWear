package com.streamlink.shared

data class StreamMetrics(
    val rttMs: Long = 0L,
    val packetLossRate: Double = 0.0,
    val bitrateKbps: Int = 0,
    val batteryLevel: Int = 100,
    val thermalLevel: Int = 0,      // 0=cool, 10=critical
    val isUserMoving: Boolean = false,
    val networkSignalStrength: Int = 4  // 0-4
)
// StreamAction is defined in StreamAction.kt — do NOT redeclare here
