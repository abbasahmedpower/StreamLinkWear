package com.streamlink.app.core

import android.content.Context
import android.util.Log
import com.streamlink.shared.DirectSocketServer
import com.streamlink.shared.GlobalStreamState
import com.streamlink.shared.LatencyTracker
import com.streamlink.shared.NetworkDiscovery
import com.streamlink.shared.StreamProtocol
import com.streamlink.shared.StreamRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

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

    private var webRtcTransport: com.streamlink.shared.WebRtcTransport? = null

    val isServerRunning: Boolean get() = socketServer.isRunning

    init {
        streamRouter.socketServer = socketServer
    }

    fun startServers() {
        scope.launch {
            startTcpServer()
            startWebRtcFallback()
        }
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

            // HOTC key exchange with 30s timeout
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
