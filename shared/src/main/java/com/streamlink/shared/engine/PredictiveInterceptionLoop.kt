package com.streamlink.shared.engine

import com.streamlink.shared.ai.LocalPredictiveEngine
import com.streamlink.shared.GlobalStreamState
import com.streamlink.shared.FramePacket
import com.streamlink.shared.StreamingIntelligenceEngine
import com.streamlink.shared.LockFreeFramePool
import com.streamlink.shared.easp.EaspStreamController
import com.streamlink.shared.easp.ZeroAllocationMotionAnalyzer
import com.streamlink.shared.easp.WristState

class PredictiveInterceptionLoop(
    private val predictiveEngine: LocalPredictiveEngine,
    private val intelEngine: StreamingIntelligenceEngine,
    private val framePool: LockFreeFramePool,
    private val motionAnalyzer: ZeroAllocationMotionAnalyzer,
    private val easpController: EaspStreamController
) {

    private val recentFrameSizes = FloatArray(5)
    private var frameIndex = 0

    /**
     * Intercepts the frame before passing it to the hardware decoder to perform
     * predictive adjustments to the streaming quality and buffer sizes.
     */
    fun interceptBeforeDecode(frame: FramePacket, currentJitterMs: Float, accX: Float, accY: Float, accZ: Float) {
        // 1. Calculate and update wrist motion state
        val wristState = motionAnalyzer.feedAndClassify(accX, accY, accZ)
        easpController.onMotionStateChanged(wristState)

        // Generate synthetic IMU variance for prediction model
        val imuVariance = if (wristState == WristState.ACTIVE_VIEWING) 2.5f else 0.5f

        // 2. Update circular buffer of recent frame sizes
        recentFrameSizes[frameIndex % 5] = frame.size.toFloat()
        frameIndex++

        // 3. Predict the next frame requirements using the TFLite edge model
        val prediction = predictiveEngine.predictNextFrameMetrics(
            last5FrameSizes = recentFrameSizes,
            currentJitter = currentJitterMs,
            imuVariance = imuVariance
        )

        // 4. Proactive adaptation (Before failure happens!)
        if (prediction.congestionRisk > 0.85f || wristState == WristState.SLEEP) {
            // Very high risk of congestion or sleep state detected! 
            // Throttle backend proactively and prepare buffers.
            intelEngine.downgradeQuality() 
        }
    }
}
