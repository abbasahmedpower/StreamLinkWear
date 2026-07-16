package com.streamlink.app.core

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import com.streamlink.shared.telemetry.SystemMetricsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * WearTelemetrySender
 *
 * Responsible for broadcasting real-time telemetry data (from the Fuzzy Engine)
 * to all connected Wear OS nodes via the Wearable Message API.
 *
 * Path: /telemetry_stream
 * Payload: JSON { battery, congestion, bitrate }
 * Frequency: Called every time the FuzzyDecisionEngine emits a new action (~1s)
 */
class WearTelemetrySender(private val context: Context) {

    private val tag = "WearTelemetrySender"
    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val nodeClient by lazy { Wearable.getNodeClient(context) }
    private val gson = Gson()

    // Dedicated IO scope with SupervisorJob so one failed send doesn't cancel all future sends
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val TELEMETRY_PATH = "/telemetry_stream"
    }

    /**
     * Serialize and broadcast the latest telemetry snapshot to all paired Wear nodes.
     * Called on every Fuzzy Engine control action (~ every 1 second).
     */
    fun sendLatestTelemetry(metrics: SystemMetricsState, currentBitrate: Int) {
        scope.launch {
            try {
                val connectedNodes = nodeClient.connectedNodes.await()

                if (connectedNodes.isEmpty()) {
                    // No watch paired — skip silently to avoid logcat spam
                    return@launch
                }

                val payload = mapOf(
                    "battery"    to metrics.batteryLevel,
                    "congestion" to (metrics.network.queueCongestion * 100).toInt(),
                    "bitrate"    to currentBitrate
                )
                val dataBytes = gson.toJson(payload).toByteArray(Charsets.UTF_8)

                connectedNodes.forEach { node ->
                    messageClient.sendMessage(node.id, TELEMETRY_PATH, dataBytes)
                    Log.d(tag, "Sent telemetry to node [${node.displayName}]: bitrate=${currentBitrate}kbps")
                }

            } catch (e: Exception) {
                // Transient failure (BT off, node unreachable) — log and continue
                Log.w(tag, "Failed to send telemetry to wear: ${e.message}")
            }
        }
    }
}
