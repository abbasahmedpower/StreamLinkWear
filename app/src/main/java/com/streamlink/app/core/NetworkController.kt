package com.streamlink.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.streamlink.shared.DirectSocketServer
import com.streamlink.shared.GlobalStreamState
import com.streamlink.shared.LatencyTracker
import com.streamlink.shared.NetworkDiscovery
import com.streamlink.shared.StreamProtocol
import com.streamlink.shared.StreamRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import java.net.Inet4Address

sealed interface NetworkTransportState {
    data object WifiLan : NetworkTransportState
    data class Hotspot(
        val gatewayIp: String,
        val interfaceName: String
    ) : NetworkTransportState
    data object BluetoothControl : NetworkTransportState
    data object Disconnected : NetworkTransportState
}

/**
 * NetworkController — owns all network transport components:
 * - DirectSocketServer (LAN TCP)
 * - WebRtcTransport (WAN relay)
 * - Socket migration on Handover
 * - Connection lifecycle
 */
@Singleton
class NetworkController @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val scope: CoroutineScope,
    private val socketServer: DirectSocketServer,
    private val streamRouter: StreamRouter,
    private val discovery: NetworkDiscovery
) {
    private val tag = "NetworkController"

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var webRtcTransport: com.streamlink.shared.WebRtcTransport? = null
    
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _rawTransportState = MutableStateFlow<NetworkTransportState>(NetworkTransportState.Disconnected)
    
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val transportState: Flow<NetworkTransportState> = _rawTransportState.debounce(1000L)

    val isServerRunning: Boolean get() = socketServer.isRunning

    init {
        streamRouter.socketServer = socketServer
        startNetworkMonitoring()
    }

    fun startServers() {
        scope.launch {
            startTcpServer()
            startWebRtcFallback()
        }
        startNetworkMonitoring()
    }

    private fun startNetworkMonitoring() {
        if (networkCallback != null) return

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                evaluateNetworkState(network, capabilities, connectivityManager.getLinkProperties(network))
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                evaluateNetworkState(network, connectivityManager.getNetworkCapabilities(network), linkProperties)
            }

            override fun onLost(network: Network) {
                _rawTransportState.value = NetworkTransportState.BluetoothControl // Fallback temporarily
                val activeNetwork = connectivityManager.activeNetwork
                if (activeNetwork != null) {
                    evaluateNetworkState(
                        activeNetwork,
                        connectivityManager.getNetworkCapabilities(activeNetwork),
                        connectivityManager.getLinkProperties(activeNetwork)
                    )
                }
            }
        }
        connectivityManager.registerNetworkCallback(request, networkCallback!!)
        
        connectivityManager.activeNetwork?.let { net ->
            evaluateNetworkState(
                net,
                connectivityManager.getNetworkCapabilities(net),
                connectivityManager.getLinkProperties(net)
            )
        }
    }

    private fun evaluateNetworkState(network: Network, capabilities: NetworkCapabilities?, linkProperties: LinkProperties?) {
        if (capabilities == null || linkProperties == null) return

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val interfaceName = linkProperties.interfaceName ?: ""
            val isHotspot = interfaceName.contains("ap", ignoreCase = true) || 
                            interfaceName.contains("wlan1", ignoreCase = true) ||
                            interfaceName.contains("swlan", ignoreCase = true)
            
            if (isHotspot) {
                val gateway = extractDynamicGatewayIp(linkProperties) ?: ""
                Log.d(tag, "Evaluated State: Hotspot ($interfaceName, $gateway)")
                _rawTransportState.value = NetworkTransportState.Hotspot(gateway, interfaceName)
            } else {
                Log.d(tag, "Evaluated State: WifiLan")
                _rawTransportState.value = NetworkTransportState.WifiLan
            }
            return
        }

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            Log.d(tag, "Evaluated State: Cellular active -> BluetoothControl fallback")
            _rawTransportState.value = NetworkTransportState.BluetoothControl
        }
    }

    /**
     * Micro-level solution: Extracts dynamic IPv4 Gateway from active routes
     * without hardcoding any subnet.
     */
    private fun extractDynamicGatewayIp(linkProperties: LinkProperties): String? {
        for (route in linkProperties.routes) {
            if (route.hasGateway() && route.gateway is Inet4Address) {
                val ip = route.gateway?.hostAddress
                if (!ip.isNullOrEmpty() && ip != "0.0.0.0") {
                    return ip
                }
            }
        }
        // Fallback for custom vendor APs: Search interface link addresses
        for (linkAddr in linkProperties.linkAddresses) {
            val address = linkAddr.address
            if (address is Inet4Address && !address.isLoopbackAddress) {
                val host = address.hostAddress
                if (host != null) {
                    val lastDot = host.lastIndexOf('.')
                    if (lastDot != -1) {
                        return host.substring(0, lastDot) + ".1"
                    }
                }
            }
        }
        return null
    }

    suspend fun startTcpServer() {
        discovery.publishService(StreamProtocol.DIRECT_SOCKET_PORT)
        socketServer.start()
    }

    private fun startWebRtcFallback() {
        val signalingUrl = try {
            com.streamlink.app.BuildConfig.SIGNALING_URL
        } catch (_: Exception) { "" }

        scope.launch {
            if (signalingUrl.isBlank()) return@launch

            val identityManager = com.streamlink.shared.security.SecureIdentityManager(context)
            val storedToken = identityManager.getStoredToken()
            val dynamicUserId = storedToken?.substringBefore(".") ?: "streamlink_unregistered"
            val identityToken = storedToken ?: ""

            if (identityToken.isBlank()) {
                Log.w(tag, "⚠️ Identity token not yet registered — WebRTC fallback skipped.")
                return@launch
            }

            val signalingClient = com.streamlink.shared.SignalingClient(
                backendUrl = signalingUrl,
                userId = dynamicUserId,
                identityToken = identityToken,
                deviceType = "PHONE"
            )

            var hotcEncryptedChannel: com.streamlink.shared.EncryptedChannel? = null
            val hotcKeyPair = com.streamlink.shared.KeyExchange.generateEphemeralKeyPair()
            val hotcChannel = com.streamlink.shared.network.WebRtcHotcChannel(context).apply {
                onEncryptedFrameReceived = { data ->
                    val ec = hotcEncryptedChannel
                    val decrypted = if (ec != null) {
                        try { ec.decrypt(data) } catch (e: Exception) {
                            Log.w(tag, "HOTC decrypt failed: ${e.message}"); null
                        }
                    } else null
                    if (decrypted != null && decrypted.size >= StreamProtocol.INPUT_FRAME_SIZE) {
                        val event = com.streamlink.shared.TouchCodec.decode(decrypted)
                        if (event != null) {
                            com.streamlink.shared.ai.TouchPerceptionHub.onRealTouch(event)
                            com.streamlink.app.control.RemoteControlAccessibilityService.instance?.handle(event)
                        }
                    }
                }
            }

            scope.launch {
                val result = withTimeoutOrNull(30_000L) {
                    signalingClient.messages.collect { msg ->
                        if (msg.optString("type") == "HOTC_KEY") {
                            val peerKey = msg.optString("payload")
                            if (com.streamlink.shared.KeyExchange.validatePeerKey(peerKey)) {
                                val code = socketServer.pairingCode ?: return@collect
                                val sessionKey = com.streamlink.shared.KeyExchange.deriveSessionKey(hotcKeyPair, peerKey, code)
                                hotcEncryptedChannel = com.streamlink.shared.EncryptedChannel(sessionKey, "tcp-stream", "P2W", "W2P")
                                Log.i(tag, "✅ HOTC session key derived over signaling")
                            } else {
                                Log.e(tag, "❌ Rejected invalid HOTC key from peer")
                            }
                        }
                    }
                }
                if (result == null) Log.w(tag, "⚠️ HOTC key exchange timed out — LAN-only mode")
            }

            scope.launch {
                signalingClient.connect()
                signalingClient.sendMessage("HOTC_KEY", "broadcast", hotcKeyPair.publicKeyBase64)
            }

            webRtcTransport = com.streamlink.shared.WebRtcTransport(
                context = context,
                signalingClient = signalingClient,
                isOfferer = false,
                hotcChannel = hotcChannel
            )
            streamRouter.webRtcTransport = webRtcTransport
            webRtcTransport?.initialize()
        }
    }

    fun stopServers() {
        socketServer.close()
        webRtcTransport?.let {
            try { it.close() } catch (e: Exception) {
                Log.e(tag, "Error closing WebRTC transport", e)
            } finally {
                webRtcTransport = null
            }
        }
        streamRouter.webRtcTransport = null
        
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(tag, "Error unregistering network callback", e)
            }
            networkCallback = null
        }
    }

    fun pauseTransport() = socketServer.pauseTransport()

    suspend fun migrateTransportSocket(newHost: String, newPort: Int, isRelay: Boolean): Boolean =
        socketServer.migrateTransportSocket(newHost, newPort, isRelay)

    fun sendControlToWatch(command: Int, value: Int) =
        socketServer.sendControlToWatch(command, value)

    val pairingCode: String? get() = socketServer.pairingCode

    fun setPairingCode(code: String?) {
        socketServer.pairingCode = code
    }
}
