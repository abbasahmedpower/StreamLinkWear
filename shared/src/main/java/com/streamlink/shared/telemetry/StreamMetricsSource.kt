package com.streamlink.shared.telemetry

import kotlinx.coroutines.flow.StateFlow

/**
 * Data snapshot of instantaneous telemetry metrics.
 */
data class MetricsSnapshot(
    val decoded: Long = 0,
    val dropped: Long = 0,
    val rtt: Long = 0,
    val reconnects: Long = 0,
    val encoderRestarts: Long = 0
)

/**
 * Single Source of Truth interface for streaming performance telemetry.
 * All UI and decision components consume metrics exclusively through this contract.
 */
interface StreamMetricsSource {
    val fps: Int
    val dropRate: Float
    val bandwidthMbps: Float
    val currentRttMs: Long

    val metricsSnapshotFlow: StateFlow<MetricsSnapshot>

    fun recordFrame(bytes: Int)
    fun recordDrop()
    fun updateRtt(rttMs: Long)
    fun reset()
}
