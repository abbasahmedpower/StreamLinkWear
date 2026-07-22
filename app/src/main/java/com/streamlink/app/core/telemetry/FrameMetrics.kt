package com.streamlink.app.core.telemetry

/**
 * Nano-level: Mutable class pre-allocated to avoid Garbage Collection in the Hot Path.
 */
class FrameMetrics {
    var frameSequence: Int = 0
    var captureTimestamp: Long = 0L
    var encoderInTimestamp: Long = 0L
    var encoderOutTimestamp: Long = 0L
    var networkTxTimestamp: Long = 0L
    var payloadSize: Int = 0
    var isKeyframe: Boolean = false
    var dropped: Boolean = false

    // Resets the object for reuse in the Ring Buffer
    fun reset(seq: Int) {
        frameSequence = seq
        captureTimestamp = 0L
        encoderInTimestamp = 0L
        encoderOutTimestamp = 0L
        networkTxTimestamp = 0L
        payloadSize = 0
        isKeyframe = false
        dropped = false
    }
}
