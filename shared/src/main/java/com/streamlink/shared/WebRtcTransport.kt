package com.streamlink.shared

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

class WebRtcTransport(
    private val context: Context,
    private val signalingClient: SignalingClient,
    private val isOfferer: Boolean
) {
    private val tag = "WebRtcTransport"
    
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    class SendTask {
        var wire: ByteArray? = null
        var size: Int = 0
    }
    private val sendQueue = LinkedBlockingQueue<SendTask>(256)
    private val freeTasks = LinkedBlockingQueue<SendTask>(256).apply {
        repeat(256) { offer(SendTask()) }
    }
    
    @Volatile var isConnected = false
    var onChunkDelivered: (() -> Unit)? = null
    var onChunkReceived: ((ByteArray, Int) -> Unit)? = null

    fun initialize() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )
        
        val options = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()
            
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        
        peerConnection = peerConnectionFactory?.createPeerConnection(
            iceServers,
            object : PeerConnection.Observer {
                override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                    Log.i(tag, "ICE State: $newState")
                    if (newState == PeerConnection.IceConnectionState.CONNECTED) {
                        isConnected = true
                    } else if (newState == PeerConnection.IceConnectionState.DISCONNECTED || newState == PeerConnection.IceConnectionState.FAILED) {
                        isConnected = false
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {}
                override fun onIceCandidate(candidate: IceCandidate) {
                    val payload = JSONObject().apply {
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                        put("candidate", candidate.sdp)
                    }
                    signalingClient.sendMessage("ICE", "broadcast", payload.toString())
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(dc: DataChannel) {
                    Log.i(tag, "Received DataChannel: ${dc.label()}")
                    setupDataChannel(dc)
                }
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {}
            }
        )

        if (isOfferer) {
            val dcInit = DataChannel.Init().apply {
                ordered = false // UDP-like behavior for video chunks
                maxRetransmits = 0
            }
            dataChannel = peerConnection?.createDataChannel("streamlink_video", dcInit)
            dataChannel?.let { setupDataChannel(it) }
            
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    peerConnection?.setLocalDescription(this, desc)
                    signalingClient.sendMessage("OFFER", "broadcast", desc.description)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String) {}
                override fun onSetFailure(error: String) {}
            }, MediaConstraints())
        }
        
        scope.launch {
            signalingClient.messages.collect { msg ->
                handleSignalingMessage(msg)
            }
        }
        
        startSenderThread()
    }

    private fun handleSignalingMessage(msg: JSONObject) {
        val type = msg.optString("type")
        val payloadStr = msg.optString("payload")
        if (payloadStr.isEmpty()) return

        when (type) {
            "OFFER" -> {
                if (!isOfferer) {
                    peerConnection?.setRemoteDescription(CustomSdpObserver(), SessionDescription(SessionDescription.Type.OFFER, payloadStr))
                    peerConnection?.createAnswer(object : CustomSdpObserver() {
                        override fun onCreateSuccess(desc: SessionDescription) {
                            peerConnection?.setLocalDescription(CustomSdpObserver(), desc)
                            signalingClient.sendMessage("ANSWER", "broadcast", desc.description)
                        }
                    }, MediaConstraints())
                }
            }
            "ANSWER" -> {
                if (isOfferer) {
                    peerConnection?.setRemoteDescription(CustomSdpObserver(), SessionDescription(SessionDescription.Type.ANSWER, payloadStr))
                }
            }
            "ICE" -> {
                val iceJson = JSONObject(payloadStr)
                val candidate = IceCandidate(
                    iceJson.getString("sdpMid"),
                    iceJson.getInt("sdpMLineIndex"),
                    iceJson.getString("candidate")
                )
                peerConnection?.addIceCandidate(candidate)
            }
        }
    }

    private fun setupDataChannel(dc: DataChannel) {
        dataChannel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                Log.i(tag, "DataChannel State: ${dc.state()}")
                isConnected = dc.state() == DataChannel.State.OPEN
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (!buffer.binary) return
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                onChunkReceived?.invoke(data, data.size)
            }
        })
    }

    private fun startSenderThread() {
        Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val task = sendQueue.take()
                    val wire = task.wire!!
                    val size = task.size
                    
                    val dc = dataChannel
                    if (dc != null && dc.state() == DataChannel.State.OPEN) {
                        val buffer = ByteBuffer.wrap(wire, 0, size)
                        val dcBuffer = DataChannel.Buffer(buffer, true)
                        dc.send(dcBuffer)
                        onChunkDelivered?.invoke()
                    }
                    WireBufferPool.release(wire)
                    task.wire = null
                    freeTasks.offer(task)
                } catch (e: Exception) {
                    break
                }
            }
        }, "SL-WebRtcSender").start()
    }

    fun sendPooledWire(wire: ByteArray, size: Int): Boolean {
        if (!isConnected || dataChannel?.state() != DataChannel.State.OPEN) {
            WireBufferPool.release(wire)
            return false
        }
        val task = freeTasks.poll()
        if (task == null) {
            WireBufferPool.release(wire)
            return false
        }
        task.wire = wire
        task.size = size
        val offered = sendQueue.offer(task)
        if (!offered) {
            task.wire = null
            freeTasks.offer(task)
            WireBufferPool.release(wire)
        }
        return offered
    }

    fun close() {
        dataChannel?.close()
        peerConnection?.close()
        peerConnectionFactory?.dispose()
    }

    open inner class CustomSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {}
        override fun onSetFailure(error: String) {}
    }
}
