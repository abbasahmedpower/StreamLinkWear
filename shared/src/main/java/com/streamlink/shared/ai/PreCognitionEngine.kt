package com.streamlink.shared.ai

import kotlin.math.sqrt

enum class TouchIntent {
    IDLE,
    TAP,
    SCROLL,
    FLICK,
    DRAG
}

data class IntentPrediction(
    val intent: TouchIntent,
    val confidence: Float,
    val sequence: Int
)

/**
 * Rule-based intent predictor (MVP). Phone-side neural model can replace [classifyIntent] later.
 */
class PreCognitionEngine(
    private val twinEngine: DigitalTwinEngine,
    private val barrier: PreCognitionBarrier = PreCognitionBarrier()
) {
    fun predict(predicted: PredictedTouch, sequence: Int, isPointerDown: Boolean): IntentPrediction {
        val speed = sqrt(
            predicted.velocityX * predicted.velocityX +
                predicted.velocityY * predicted.velocityY
        )
        val accel = twinEngine.twin().getAcceleration()

        val intent = classifyIntent(speed, accel, isPointerDown)
        val confidence = twinEngine.twin().getConfidence().coerceIn(0f, 1f)

        return IntentPrediction(intent, confidence, sequence)
    }

    fun shouldPreExecute(prediction: IntentPrediction): Boolean {
        val threshold = when (prediction.intent) {
            TouchIntent.TAP -> 0.92f
            TouchIntent.SCROLL -> 0.85f
            TouchIntent.FLICK -> 0.88f
            TouchIntent.DRAG -> 0.80f
            TouchIntent.IDLE -> 1f
        }
        return barrier.evaluateTransaction(prediction.sequence, prediction.confidence, threshold)
    }

    private fun classifyIntent(speed: Float, acceleration: Float, isPointerDown: Boolean): TouchIntent {
        return when {
            !isPointerDown && speed < 0.002f -> TouchIntent.TAP
            speed > 0.025f && acceleration > 0.02f -> TouchIntent.FLICK
            speed > 0.008f -> TouchIntent.SCROLL
            isPointerDown && speed > 0.001f -> TouchIntent.DRAG
            else -> TouchIntent.IDLE
        }
    }
}
