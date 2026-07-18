package com.streamlink.app.core

import android.util.Log
import com.streamlink.shared.GlobalStreamState
import com.streamlink.shared.LatencyTracker
import com.streamlink.shared.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TelemetryCollector — observes the active stream state and pushes
 * performance metrics (FPS, bitrate, latency, packet loss) to the backend
 * via the signaling channel.
 *
 * Decoupled from NetworkController to avoid circular dependency and to allow
 * independent testing.
 */
@Singleton
class TelemetryCollector @Inject constructor(
    private val scope: CoroutineScope,
    private val latencyTracker: LatencyTracker
) {
    private val tag = "TelemetryCollector"

    /** Set by NetworkController once the signaling client is connected. */
    @Volatile var signalingClient: SignalingClient? = null

    fun startCollection() {
        scope.launch {
            GlobalStreamState.snapshot.collect { state ->
                val client = signalingClient ?: return@collect
                if (state.fps > 0 || state.bitrateKbps > 0) {
                    try {
                        val report = latencyTracker.report()
                        val payload = JSONObject().apply {
                            put("fps", state.fps)
                            put("bitrateKbps", state.bitrateKbps)
                            put("packetLossPercent", report.lateFramePct)
                            if (report.avgE2EMs > 0) put("latencyMs", report.avgE2EMs)
                            if (report.jitterMs > 0) put("jitterMs", report.jitterMs)
                        }
                        client.sendMessage("METRICS", "broadcast", payload.toString())
                    } catch (e: Exception) {
                        Log.w(tag, "Telemetry send failed: ${e.message}")
                    }
                }
            }
        }
    }
}
