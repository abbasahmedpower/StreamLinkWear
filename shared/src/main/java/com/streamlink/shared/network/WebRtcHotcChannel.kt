package com.streamlink.shared.network

import android.content.Context
import android.util.Log
import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dedicated HOTC data channel — unordered/unreliable SCTP for 32-byte encrypted touch frames.
 * Attach to an existing [PeerConnection] created by [com.streamlink.shared.WebRtcTransport].
 */
class WebRtcHotcChannel(
    private val context: Context
) : WebRtcHotcSender {
    private val tag = "WebRtcHotcChannel"
    private var dataChannel: DataChannel? = null
    private val ready = AtomicBoolean(false)

    var onEncryptedFrameReceived: ((ByteArray) -> Unit)? = null

    fun attachToPeerConnection(peerConnection: PeerConnection, asOfferer: Boolean) {
        if (asOfferer) {
            val init = DataChannel.Init().apply {
                ordered = false
                maxRetransmits = 0
                maxPacketLifeTime = 16
                id = 2
            }
            dataChannel = peerConnection.createDataChannel(HOTC_LABEL, init)
            dataChannel?.let { setupCallbacks(it) }
        }
    }

    fun onRemoteDataChannel(dc: DataChannel) {
        if (dc.label() == HOTC_LABEL) {
            dataChannel = dc
            setupCallbacks(dc)
        }
    }

    private fun setupCallbacks(dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                val open = dc.state() == DataChannel.State.OPEN
                ready.set(open)
                Log.i(tag, "HOTC channel state=${dc.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (!buffer.binary) return
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                onEncryptedFrameReceived?.invoke(data)
            }
        })
    }

    override fun sendFrame(encryptedPayload: ByteArray): Boolean {
        val dc = dataChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        val buffer = ByteBuffer.wrap(encryptedPayload)
        return dc.send(DataChannel.Buffer(buffer, true))
    }

    override fun isReady(): Boolean = ready.get()

    fun close() {
        ready.set(false)
        dataChannel?.close()
        dataChannel = null
    }

    companion object {
        const val HOTC_LABEL = "streamlink_touch"
    }
}
