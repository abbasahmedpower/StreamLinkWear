package com.streamlink.shared.network

import java.util.concurrent.atomic.AtomicReference

enum class NetworkTransportMode {
    LOCAL_TCP,
    GLOBAL_WEBRTC,
    DISCONNECTED
}

/**
 * Lock-free transport selector: local TCP first, seamless WebRTC HOTC fallback.
 */
class NetworkFallbackOrchestrator(
    private val localSender: LocalTouchSender,
    private val webRtcSender: WebRtcHotcSender?
) {
    private val currentMode = AtomicReference(NetworkTransportMode.DISCONNECTED)

    fun setTransportMode(mode: NetworkTransportMode) {
        currentMode.set(mode)
    }

    fun currentMode(): NetworkTransportMode = currentMode.get()

    fun dispatchEncryptedFrame(encryptedPayload: ByteArray): Boolean {
        return when (currentMode.get()) {
            NetworkTransportMode.LOCAL_TCP -> {
                val success = localSender.send(encryptedPayload)
                if (!success && webRtcSender != null) {
                    currentMode.set(NetworkTransportMode.GLOBAL_WEBRTC)
                    webRtcSender.sendFrame(encryptedPayload)
                } else {
                    success
                }
            }
            NetworkTransportMode.GLOBAL_WEBRTC -> {
                webRtcSender?.sendFrame(encryptedPayload) ?: false
            }
            NetworkTransportMode.DISCONNECTED -> false
        }
    }

    fun markLocalConnected() {
        currentMode.set(NetworkTransportMode.LOCAL_TCP)
    }

    fun markDisconnected() {
        currentMode.set(NetworkTransportMode.DISCONNECTED)
    }
}

interface LocalTouchSender {
    fun send(encryptedPayload: ByteArray): Boolean
    fun isConnected(): Boolean
}

interface WebRtcHotcSender {
    fun sendFrame(encryptedPayload: ByteArray): Boolean
    fun isReady(): Boolean
}
