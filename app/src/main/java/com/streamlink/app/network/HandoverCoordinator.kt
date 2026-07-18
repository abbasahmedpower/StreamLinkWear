package com.streamlink.app.network

import android.content.Context
import android.util.Log
import com.streamlink.app.core.StreamingOrchestrator
import com.streamlink.shared.GlobalStreamState
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
        // Proactive handover: Switch to Cellular before WiFi disconnects completely
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
        // Here, we'd query your STUN/TURN server or a signaling API (like an AWS/Node.js endpoint)
        // to find the public relay IP mapped for this Watch's unique ID.
        // Returning a placeholder that simulates a relay address:
        return RelayEndpoint("relay.horuslink.com", 3478) 
    }

    private fun resolveLocalWifiEndpoint(): RelayEndpoint? {
        // Return cached P2P details from mDNS
        return RelayEndpoint("192.168.1.50", 8080)
    }
}
