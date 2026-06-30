package com.streamlink.shared

import android.util.Log

/**
 * Routes video frames to TCP (DirectSocketServer) or WebRTC (WebRtcTransport).
 * Ensures zero-drop failover if TCP disconnects.
 * Active-Passive strategy: Prefers TCP, falls back to WebRTC.
 */
class StreamRouter {
    private val tag = "StreamRouter"
    
    var socketServer: DirectSocketServer? = null
    var webRtcTransport: WebRtcTransport? = null

    fun sendPooledWire(wire: ByteArray, size: Int): Boolean {
        val ss = socketServer
        if (ss != null && ss.isClientConnected) {
            return ss.sendPooledWire(wire, size)
        }
        
        val wrtc = webRtcTransport
        if (wrtc != null && wrtc.isConnected) {
            return wrtc.sendPooledWire(wire, size)
        }
        
        // Neither is connected
        WireBufferPool.release(wire)
        return false
    }
    
    val queueDepth: Int
        get() {
            val ss = socketServer
            if (ss != null && ss.isClientConnected) {
                return ss.queueDepth
            }
            val wrtc = webRtcTransport
            if (wrtc != null && wrtc.isConnected) {
                return wrtc.queueDepth
            }
            return 0
        }
}
