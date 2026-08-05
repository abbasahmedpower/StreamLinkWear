package com.streamlink.shared

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import com.streamlink.shared.util.LockFreeMpmcQueue
import com.streamlink.shared.util.LockFreeSpscQueue

class WebRtcTransport(
    private val context: Context,
    private val signalingClient: SignalingClient,
    private val isOfferer: Boolean,
    private val hotcChannel: com.streamlink.shared.network.WebRtcHotcChannel? = null
) {
    private val tag = "WebRtcTransport"
    
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()) // ✅ FIX #15: SupervisorJob — فشل coroutine واحد لا يُلغي الـ scope كله
    class SendTask {
        var wire: ByteArray? = null
        var size: Int = 0
    }
    private val sendQueue = LockFreeMpmcQueue<SendTask>(256)
    private val freeTasks = LockFreeMpmcQueue<SendTask>(256).apply {
        repeat(256) { offer(SendTask()) }
    }
    
    // ✅ 4.2: Track sender thread for proper lifecycle management
    private var senderThread: Thread? = null
    private val senderRunning = AtomicBoolean(true)

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
                    if (dc.label() == com.streamlink.shared.network.WebRtcHotcChannel.HOTC_LABEL) {
                        hotcChannel?.onRemoteDataChannel(dc)
                    } else {
                        setupDataChannel(dc)
                    }
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

            peerConnection?.let { pc ->
                hotcChannel?.attachToPeerConnection(pc, asOfferer = true)
            }
            
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    peerConnection?.setLocalDescription(this, desc)
                    signalingClient.sendMessage("OFFER", "broadcast", desc.description)
                }
                override fun onSetSuccess() {}
                // ✅ 5.2: SDP failure callbacks must log — silent failure blocks connection forever
                override fun onCreateFailure(error: String) {
                    Log.e(tag, "SDP offer create failed: $error")
                }
                override fun onSetFailure(error: String) {
                    Log.e(tag, "SDP offer set failed: $error")
                }
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
        // ✅ 4.2: Thread is tracked for lifecycle management; errors are logged, not fatal
        senderThread = Thread({
            while (senderRunning.get() && !Thread.currentThread().isInterrupted) {
                try {
                    val task = sendQueue.poll()
                    if (task == null) {
                        LockSupport.parkNanos(100_000)
                        continue
                    }
                    val wire = task.wire
                    if (wire == null) {
                        freeTasks.offer(task)
                        continue
                    }
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
                } catch (e: InterruptedException) {
                    // ✅ Clean, intentional shutdown — not an error
                    break
                } catch (e: Exception) {
                    // ✅ 4.2: Log and CONTINUE — a transient error must not kill the sender permanently
                    Log.e(tag, "Sender loop error (recovered): ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            Log.i(tag, "Sender thread exiting cleanly")
        }, "SL-WebRtcSender").apply {
            isDaemon = true
            start()
        }
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

    val queueDepth: Int get() = sendQueue.size

    fun close() {
        // ✅ 4.3: Stop background resources in correct order to prevent leaks
        // 1. Signal the sender loop to stop
        senderRunning.set(false)
        // 2. Interrupt the thread + unpark it immediately.
        //    LockSupport.parkNanos() does NOT respond to interrupt() the way Thread.sleep() does
        //    (it doesn't throw InterruptedException). unpark() wakes it immediately from the park.
        senderThread?.let { t ->
            t.interrupt()
            LockSupport.unpark(t) // ✅ Nano fix: instant wake from parkNanos(100_000)
        }
        // 3. Cancel the coroutine scope collecting signaling messages
        scope.cancel()
        // 4. Then tear down WebRTC resources
        dataChannel?.close()
        peerConnection?.close()
        peerConnectionFactory?.dispose()
    }

    open inner class CustomSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        // ✅ 5.2: Log SDP failures — silent failure here causes "stuck connecting" with no trace
        override fun onCreateFailure(error: String) {
            Log.e(tag, "SDP create failed: $error")
        }
        override fun onSetFailure(error: String) {
            Log.e(tag, "SDP set failed: $error")
        }
    }
}
