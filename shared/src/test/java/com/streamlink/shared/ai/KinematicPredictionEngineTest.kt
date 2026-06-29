package com.streamlink.shared.ai

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KinematicPredictionEngineTest {

    @Test
    fun predict_moves_forward_with_velocity() {
        val engine = KinematicPredictionEngine()
        engine.updateAndPredict(0.1f, 0.1f, 16f, 16f)
        val result = engine.updateAndPredict(0.2f, 0.1f, 16f, 16f)

        assertTrue(result.predictedX >= result.smoothedX)
        assertTrue(result.velocityX >= 0f)
    }

    @Test
    fun hysteresis_zeros_micro_jitter() {
        val engine = KinematicPredictionEngine(jitterThreshold = 0.01f)
        engine.updateAndPredict(0.5f, 0.5f, 16f)
        val result = engine.updateAndPredict(0.5005f, 0.5005f, 16f)

        assertTrue(result.velocityX == 0f)
        assertTrue(result.velocityY == 0f)
    }
}
