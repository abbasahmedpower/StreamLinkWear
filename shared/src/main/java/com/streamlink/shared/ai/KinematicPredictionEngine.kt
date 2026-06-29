package com.streamlink.shared.ai

import kotlin.math.abs

/**
 * Kinematic prediction with adaptive Kalman smoothing and hysteresis
 * to suppress micro-jitter without rubber-banding on sharp direction changes.
 */
class KinematicPredictionEngine(
    private val qProcessNoise: Float = 0.005f,
    private val rMeasurementNoise: Float = 0.04f,
    private val jitterThreshold: Float = 0.002f
) {
    private var xEst = 0f
    private var pX = 1f
    private var yEst = 0f
    private var pY = 1f

    private var lastX = 0f
    private var lastY = 0f
    private var velocityX = 0f
    private var velocityY = 0f
    private var lastDeltaMs = 0f

    fun reset() {
        xEst = 0f
        pX = 1f
        yEst = 0f
        pY = 1f
        lastX = 0f
        lastY = 0f
        velocityX = 0f
        velocityY = 0f
        lastDeltaMs = 0f
    }

    /**
     * @param rawX normalized x in [0, 1]
     * @param rawY normalized y in [0, 1]
     * @param deltaTimeMs elapsed since last sample in milliseconds
     * @param predictionWindowMs how far ahead to extrapolate (e.g. 16ms)
     */
    fun updateAndPredict(
        rawX: Float,
        rawY: Float,
        deltaTimeMs: Float,
        predictionWindowMs: Float = 16f
    ): PredictedTouch {
        if (deltaTimeMs <= 0f) {
            return PredictedTouch(rawX, rawY, velocityX, velocityY, rawX, rawY)
        }

        pX += qProcessNoise
        val kGainX = pX / (pX + rMeasurementNoise)
        xEst += kGainX * (rawX - xEst)
        pX *= (1f - kGainX)

        pY += qProcessNoise
        val kGainY = pY / (pY + rMeasurementNoise)
        yEst += kGainY * (rawY - yEst)
        pY *= (1f - kGainY)

        val currentVelocityX = (xEst - lastX) / deltaTimeMs
        val currentVelocityY = (yEst - lastY) / deltaTimeMs

        velocityX = if (abs(currentVelocityX) > jitterThreshold) currentVelocityX else 0f
        velocityY = if (abs(currentVelocityY) > jitterThreshold) currentVelocityY else 0f

        lastX = xEst
        lastY = yEst
        lastDeltaMs = deltaTimeMs

        val predictedX = (xEst + velocityX * predictionWindowMs).coerceIn(0f, 1f)
        val predictedY = (yEst + velocityY * predictionWindowMs).coerceIn(0f, 1f)

        return PredictedTouch(
            smoothedX = xEst,
            smoothedY = yEst,
            velocityX = velocityX,
            velocityY = velocityY,
            predictedX = predictedX,
            predictedY = predictedY
        )
    }

    fun isHighAcceleration(threshold: Float = 0.015f): Boolean {
        if (lastDeltaMs <= 0f) return false
        val ax = abs(velocityX) / lastDeltaMs
        val ay = abs(velocityY) / lastDeltaMs
        return ax > threshold || ay > threshold
    }
}

data class PredictedTouch(
    val smoothedX: Float,
    val smoothedY: Float,
    val velocityX: Float,
    val velocityY: Float,
    val predictedX: Float,
    val predictedY: Float
)
