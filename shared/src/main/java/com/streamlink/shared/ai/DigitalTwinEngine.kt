package com.streamlink.shared.ai

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Evolves [AlignedUserTwin] metrics from kinematic touch samples.
 * Runs on wear for lightweight feature extraction; phone uses twin for intent thresholds.
 */
class DigitalTwinEngine(
    private val twin: AlignedUserTwin = AlignedUserTwin()
) {
    private var emaConfidence = 0.5f
    private var emaReactionMs = 32f

    fun twin(): AlignedUserTwin = twin

    fun ingest(predicted: PredictedTouch, deltaTimeMs: Float) {
        val speed = sqrt(
            predicted.velocityX * predicted.velocityX +
                predicted.velocityY * predicted.velocityY
        )
        val accel = if (deltaTimeMs > 0f) speed / deltaTimeMs else 0f

        emaReactionMs = lerp(emaReactionMs, deltaTimeMs, 0.08f)
        val confidenceBoost = when {
            speed < 0.001f -> 0.002f
            accel > 0.02f -> 0.015f
            else -> 0.005f
        }
        emaConfidence = (emaConfidence + confidenceBoost).coerceIn(0f, 1f)

        twin.updateReactionTimeMs(emaReactionMs)
        twin.updateMetricsRaw(speed, accel, emaConfidence)
    }

    fun penalizeMisprediction() {
        emaConfidence = (emaConfidence - 0.08f).coerceAtLeast(0.1f)
        twin.updateMetricsRaw(twin.getVelocity(), twin.getAcceleration(), emaConfidence)
    }

    fun rewardCorrectPrediction() {
        emaConfidence = (emaConfidence + 0.03f).coerceAtMost(1f)
        twin.updateMetricsRaw(twin.getVelocity(), twin.getAcceleration(), emaConfidence)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
