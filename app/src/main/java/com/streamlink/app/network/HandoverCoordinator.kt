package com.streamlink.app.network

import android.content.Context
import android.util.Log
import com.streamlink.app.core.NetworkController
import com.streamlink.app.core.NetworkTransportState
import com.streamlink.app.core.StreamingOrchestrator
import com.streamlink.shared.GlobalStreamState
import com.streamlink.shared.StreamProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

interface TransportHandler {
    suspend fun enter()
    suspend fun exit()
}

/**
 * Manages the transition and coordination during network handovers.
 * Guarantees that the stream's transport socket is updated dynamically.
 * Implements a robust state machine driven by NetworkController's transportState.
 */
class HandoverCoordinator(
    private val context: Context,
    private val orchestrator: StreamingOrchestrator,
    private val networkController: NetworkController
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var observationJob: Job? = null
    private var currentHandler: TransportHandler? = null

    fun start() {
        if (observationJob != null) return
        observationJob = scope.launch {
            networkController.transportState.collect { state ->
                handleStateTransition(state)
            }
        }
    }

    fun stop() {
        observationJob?.cancel()
        observationJob = null
    }

    private suspend fun handleStateTransition(state: NetworkTransportState) {
        if (GlobalStreamState.snapshot.value.state != GlobalStreamState.State.STREAMING) {
            return
        }

        val nextHandler = when (state) {
            is NetworkTransportState.WifiLan -> WifiLanHandler(orchestrator)
            is NetworkTransportState.Hotspot -> HotspotHandler(state, orchestrator)
            is NetworkTransportState.BluetoothControl -> BluetoothControlHandler(orchestrator)
            is NetworkTransportState.Disconnected -> DisconnectedHandler(orchestrator)
        }

        if (currentHandler?.javaClass == nextHandler.javaClass) {
            // Already in this handler type. Check if internal properties changed (e.g. Hotspot IP)
            if (currentHandler is HotspotHandler && state is NetworkTransportState.Hotspot) {
                if ((currentHandler as HotspotHandler).state != state) {
                    currentHandler?.exit()
                    currentHandler = nextHandler
                    currentHandler?.enter()
                }
            }
            return
        }

        Log.i("HandoverCoordinator", "Transitioning to ${nextHandler.javaClass.simpleName}")
        currentHandler?.exit()
        currentHandler = nextHandler
        currentHandler?.enter()
    }
}

class WifiLanHandler(private val orchestrator: StreamingOrchestrator) : TransportHandler {
    override suspend fun enter() {
        Log.i("WifiLanHandler", "Entering WifiLan state. Handing back to Local P2P.")
        val host = orchestrator.lastKnownLocalHost()
        if (host != null) {
            val success = orchestrator.migrateTransportSocket(host, StreamProtocol.DIRECT_SOCKET_PORT, false)
            if (success) {
                Log.i("WifiLanHandler", "Gracefully migrated back to local WiFi.")
                orchestrator.resumeVideo()
                orchestrator.triggerInstantSync()
            }
        } else {
            Log.w("WifiLanHandler", "No mDNS-discovered watch host cached yet.")
        }
    }

    override suspend fun exit() {
        Log.d("WifiLanHandler", "Exiting WifiLan state.")
    }
}

class HotspotHandler(
    val state: NetworkTransportState.Hotspot,
    private val orchestrator: StreamingOrchestrator
) : TransportHandler {
    override suspend fun enter() {
        Log.i("HotspotHandler", "Entering Hotspot state (Gateway: ${state.gatewayIp}). Initiating handshake...")
        // User requested: Probe -> Handshake -> Reconnect logic
        if (state.gatewayIp.isNotBlank()) {
            val success = orchestrator.migrateTransportSocket(state.gatewayIp, StreamProtocol.DIRECT_SOCKET_PORT, false)
            if (success) {
                Log.i("HotspotHandler", "Successfully reconnected on Hotspot AP.")
                orchestrator.resumeVideo()
                orchestrator.triggerInstantSync()
            } else {
                Log.e("HotspotHandler", "Failed to handshake on Hotspot gateway.")
            }
        }
    }

    override suspend fun exit() {
        Log.d("HotspotHandler", "Exiting Hotspot state.")
    }
}

class BluetoothControlHandler(private val orchestrator: StreamingOrchestrator) : TransportHandler {
    override suspend fun enter() {
        Log.i("BluetoothControlHandler", "Entering BluetoothControl state. Pausing Video, maintaining Session.")
        orchestrator.pauseVideo() // Pausing Video Plane safely instead of tearing down transport
        orchestrator.startBluetoothHeartbeat()
        orchestrator.reportTransportIssue(
            code = "BT_CONTROL_ONLY",
            message = "Out of Wi-Fi scope. Turn on Phone Hotspot & connect Watch, or continue via Bluetooth control only."
        )
    }

    override suspend fun exit() {
        orchestrator.stopBluetoothHeartbeat()
        Log.d("BluetoothControlHandler", "Exiting BluetoothControl state.")
    }
}

class DisconnectedHandler(private val orchestrator: StreamingOrchestrator) : TransportHandler {
    override suspend fun enter() {
        Log.e("DisconnectedHandler", "All networks disconnected. Pausing stream.")
        orchestrator.pauseVideo()
        orchestrator.pauseTransport()
        orchestrator.reportTransportIssue(
            code = "NETWORK_LOST",
            message = "مفيش اتصال شبكة — البث اتوقف مؤقتاً"
        )
    }

    override suspend fun exit() {
        Log.d("DisconnectedHandler", "Exiting Disconnected state.")
    }
}
