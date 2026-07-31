package com.streamlink.shared.telemetry

import kotlinx.coroutines.flow.StateFlow

/**
 * Data snapshot of instantaneous telemetry metrics.
 *
 * Phase 3 — Telemetry Unification:
 * decodeTimeMs / renderTimeMs absorb what used to live in the wear-only
 * FrameMetricsCollector (LiveFrameStats). reconnects absorbs the counter
 * that used to live in StreamObservability. There is now exactly one
 * snapshot type for the whole app — phone and watch alike.
 */
data class MetricsSnapshot(
    val decoded: Long = 0,
    val dropped: Long = 0,
    val rtt: Long = 0,
    val reconnects: Long = 0,
    val encoderRestarts: Long = 0,
    val decodeTimeMs: Float = 0f,
    val renderTimeMs: Float = 0f
) {
    /** End-to-end per-frame latency budget: decode + render (matches old "P99 LATENCY" row). */
    val totalFrameLatencyMs: Float get() = decodeTimeMs + renderTimeMs
}

/**
 * Single Source of Truth interface for streaming performance telemetry.
 * All UI and decision components consume metrics exclusively through this contract.
 *
 * Phase 3 note: this interface previously had three parallel siblings that each
 * tracked an overlapping slice of the same concepts (StreamObservability tracked
 * frames-sent/drops/reconnects; telemetry.TelemetryCollector tracked fps/latency/
 * bandwidth over a rolling window; wear's FrameMetricsCollector tracked decode/
 * render timing). They have all been folded into this single contract so there
 * is one call site per event and one flow to observe.
 */
interface StreamMetricsSource {
    val fps: Int
    val dropRate: Float
    val bandwidthMbps: Float
    val currentRttMs: Long

    val metricsSnapshotFlow: StateFlow<MetricsSnapshot>

    fun recordFrame(bytes: Int)
    fun recordDrop()
    fun recordReconnect()
    /** Per-frame decode/render timing, previously tracked only on the wear side. */
    fun recordFrameTiming(decodeMs: Float, renderMs: Float)
    fun updateRtt(rttMs: Long)
    fun reset()

    companion object {
        /**
         * Process-wide accessor for call sites that cannot receive [StreamMetricsSource]
         * through constructor injection (plain android.view.View instances such as
         * HardenedStreamTextureView, created via AndroidView { } factories rather than
         * through Hilt). The Hilt-provided [MetricsCollector] singleton publishes itself
         * here on creation. Everything that CAN be constructor-injected still should be —
         * this is a deliberate escape hatch for the View layer only, matching the existing
         * GlobalStreamState pattern already used elsewhere in this codebase.
         */
        @Volatile
        var active: StreamMetricsSource? = null
    }
}
