package com.streamlink.app.network

import android.content.Context
import android.util.Log
import com.streamlink.app.BuildConfig
import com.streamlink.app.core.StreamingOrchestrator
import com.streamlink.shared.GlobalStreamState
import com.streamlink.shared.StreamProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Manages the transition and coordination during network handovers.
 * Guarantees that the stream's transport socket is updated dynamically.
 */
class HandoverCoordinator(
    context: Context,
    private val orchestrator: StreamingOrchestrator
) : NetworkPathMonitor.HandoverListener {

    private val pathMonitor = NetworkPathMonitor(context)
    private val scope = CoroutineScope(Dispatchers.Default)
    private val isHandoverInProcess = java.util.concurrent.atomic.AtomicBoolean(false)

    fun start() {
        pathMonitor.setListener(this)
        pathMonitor.startMonitoring()
    }

    fun stop() {
        pathMonitor.stopMonitoring()
    }

    override fun onPathChanged(newPath: NetworkPathMonitor.NetworkPath) {
        scope.launch {
            handlePathChange(newPath)
        }
    }

    override fun onCriticalSignalDegradation() {
        // Proactive handover: Switch to Cellular before WiFi disconnects completely.
        // NANO-FIX: this was a second, unguarded entry point into executeHandoverToCellular()
        // — gated it behind the same BuildConfig.WAN_RELAY_ENABLED flag as handlePathChange()
        // so it can't hit the not-yet-deployed relay either.
        if (!BuildConfig.WAN_RELAY_ENABLED) return
        if (!isHandoverInProcess.get() && pathMonitor.currentPath.value == NetworkPathMonitor.NetworkPath.WIFI) {
            Log.w("HandoverCoordinator", "Initiating proactive handover due to poor WiFi RSSI.")
            scope.launch {
                executeHandoverToCellular()
            }
        }
    }

    private suspend fun handlePathChange(path: NetworkPathMonitor.NetworkPath) {
        if (GlobalStreamState.snapshot.value.state != GlobalStreamState.State.STREAMING) {
            return // No active stream, ignore network shifts
        }

        when (path) {
            NetworkPathMonitor.NetworkPath.CELLULAR -> {
                if (!BuildConfig.WAN_RELAY_ENABLED) {
                    // NANO-FIX: the TURN/relay backend for cross-network handover is not
                    // deployed yet (see README "Phase 2"). Previously this branch was
                    // unconditional and would silently try to reach a relay host that
                    // doesn't exist, freezing the stream with no explanation. Until the
                    // relay ships, pause cleanly and tell the user exactly what happened.
                    Log.w("HandoverCoordinator", "WiFi lost — WAN relay not enabled yet, pausing stream safely.")
                    orchestrator.pauseTransport()
                    orchestrator.reportTransportIssue(
                        code = "WAN_RELAY_UNAVAILABLE",
                        message = "خرجت من نطاق الواي فاي — البث اتوقف مؤقتاً لحد ما ترجع للشبكة"
                    )
                    return
                }
                Log.i("HandoverCoordinator", "WiFi Lost. Executing hard handover to Cellular WAN.")
                executeHandoverToCellular()
            }
            NetworkPathMonitor.NetworkPath.WIFI -> {
                Log.i("HandoverCoordinator", "WiFi recovered. Handing back to Local P2P for zero latency.")
                executeHandoverToLocalWifi()
            }
            NetworkPathMonitor.NetworkPath.DISCONNECTED -> {
                Log.e("HandoverCoordinator", "All networks disconnected. Pausing stream.")
                orchestrator.pauseTransport() // Custom method on orchestrator to hold encoding pipeline
                orchestrator.reportTransportIssue(
                    code = "NETWORK_LOST",
                    message = "مفيش اتصال شبكة — البث اتوقف مؤقتاً"
                )
            }
        }
    }

    private suspend fun executeHandoverToCellular() {
        if (!isHandoverInProcess.compareAndSet(false, true)) return
        
        try {
            Log.d("HandoverCoordinator", "[HANDOVER] Step 1: Pausing active socket transport...")
            orchestrator.pauseTransport() 

            Log.d("HandoverCoordinator", "[HANDOVER] Step 2: Fetching WAN relay candidates (TURN)...")
            val wanTargetAddress = resolveWanRelayEndpoint()

            Log.d("HandoverCoordinator", "[HANDOVER] Step 3: Re-routing socket to WAN endpoint: $wanTargetAddress")
            val success = orchestrator.migrateTransportSocket(
                newHost = wanTargetAddress.ip,
                newPort = wanTargetAddress.port,
                isRelay = true
            )

            if (success) {
                Log.i("HandoverCoordinator", "[HANDOVER] Step 4: Socket migrated successfully to WAN. Resuming Stream!")
            } else {
                Log.e("HandoverCoordinator", "[HANDOVER] Failed to migrate socket. Fallback to manual entry.")
            }
        } catch (e: Exception) {
            Log.e("HandoverCoordinator", "Error during handover execution", e)
        } finally {
            isHandoverInProcess.set(false)
        }
    }

    private suspend fun executeHandoverToLocalWifi() {
        if (!isHandoverInProcess.compareAndSet(false, true)) return
        
        try {
            orchestrator.pauseTransport()
            // Pull the resolved mDNS address from your local pairing/discovery cache
            val localAddress = resolveLocalWifiEndpoint()
            
            if (localAddress != null) {
                val success = orchestrator.migrateTransportSocket(
                    newHost = localAddress.ip,
                    newPort = localAddress.port,
                    isRelay = false
                )
                if (success) {
                    Log.i("HandoverCoordinator", "[HANDOVER] Gracefully migrated back to local WiFi.")
                }
            }
        } finally {
            isHandoverInProcess.set(false)
        }
    }

    // Helper data structures for relay mapping
    private data class RelayEndpoint(val ip: String, val port: Int)

    private suspend fun resolveWanRelayEndpoint(): RelayEndpoint {
        // This path is only reachable when BuildConfig.WAN_RELAY_ENABLED is true —
        // i.e. once a real TURN/relay backend is deployed and its address is wired
        // through BuildConfig (same pattern as SIGNALING_URL), NOT hardcoded here.
        require(BuildConfig.WAN_RELAY_ENABLED) {
            "resolveWanRelayEndpoint() called while WAN relay is disabled — this is a bug in the caller."
        }
        return RelayEndpoint(BuildConfig.WAN_RELAY_HOST, BuildConfig.WAN_RELAY_PORT)
    }

    private fun resolveLocalWifiEndpoint(): RelayEndpoint? {
        // NANO-FIX: previously hardcoded to "192.168.1.50:8080" — a fake address that
        // doesn't correspond to any real device, on the wrong port too (direct socket
        // port is 8999, see StreamProtocol.DIRECT_SOCKET_PORT). Read the actual
        // mDNS/NSD-discovered host that StreamingOrchestrator's NetworkDiscovery
        // instance already maintains.
        val host = orchestrator.lastKnownLocalHost()
        if (host == null) {
            Log.w("HandoverCoordinator", "No mDNS-discovered watch host cached yet — cannot hand back to local WiFi.")
            return null
        }
        return RelayEndpoint(host, StreamProtocol.DIRECT_SOCKET_PORT)
    }
}
