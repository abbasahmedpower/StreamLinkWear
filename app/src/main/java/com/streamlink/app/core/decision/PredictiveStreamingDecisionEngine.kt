package com.streamlink.app.core.decision

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * High-level decision engine managing macroeconomic streaming states.
 * Renamed from DecisionEngine to provide clear architectural boundary.
 */
class PredictiveStreamingDecisionEngine {

    private val _decisionFlow = MutableStateFlow(
        StreamingDecision(StreamingProfile.ULTRA, 100f, false)
    )
    val decisionFlow: StateFlow<StreamingDecision> = _decisionFlow.asStateFlow()

    private val predictiveEvaluator = com.streamlink.app.core.predictive.PredictiveDecisionEvaluator()

    // Hysteresis counters
    private var consecutiveGoodScores = 0
    private val RECOVERY_CYCLES_REQUIRED = 6 // e.g., 3 seconds if interval is 500ms

    fun evaluate(snapshot: TelemetrySnapshot) {
        var healthScore = HeuristicsEvaluator.calculateHealthScore(snapshot)
        
        // --- Predictive Layer ---
        val sample = com.streamlink.app.core.predictive.NetworkSample(
            timestamp = System.currentTimeMillis(),
            rtt = snapshot.rttMs,
            jitter = 0,
            packetLoss = snapshot.packetLossPercent,
            bitrate = 0
        )
        
        val riskScore = predictiveEvaluator.evaluateRisk(sample, snapshot.batteryPercent)
        
        // Apply Risk Score to Health Score (Proactive adjustment)
        if (riskScore > 0f) {
            healthScore -= (riskScore * 50f)
            healthScore = healthScore.coerceAtLeast(0f)
        }

        val currentProfile = _decisionFlow.value.targetProfile
        
        // Map score to target profile
        val targetProfile = when {
            healthScore >= 85f -> StreamingProfile.ULTRA
            healthScore >= 70f -> StreamingProfile.HIGH
            healthScore >= 50f -> StreamingProfile.BALANCED
            else -> StreamingProfile.SURVIVAL
        }

        // --- Hysteresis Logic ---
        if (targetProfile.ordinal > currentProfile.ordinal) {
            // DOWNGRADE: Apply IMMEDIATELY
            consecutiveGoodScores = 0
            emitDecision(targetProfile, healthScore, immediateAction = true)
        } else if (targetProfile.ordinal < currentProfile.ordinal) {
            // UPGRADE: Require sustained stability
            consecutiveGoodScores++
            if (consecutiveGoodScores >= RECOVERY_CYCLES_REQUIRED) {
                consecutiveGoodScores = 0
                emitDecision(targetProfile, healthScore, immediateAction = false)
            }
        } else {
            // STABLE: Update score if changed significantly
            consecutiveGoodScores = 0
            if (Math.abs(_decisionFlow.value.healthScore - healthScore) > 2f) {
                emitDecision(currentProfile, healthScore, immediateAction = false)
            }
        }
    }

    fun updateDecision(newDecision: StreamingDecision) {
        _decisionFlow.value = newDecision
    }

    private fun emitDecision(profile: StreamingProfile, score: Float, immediateAction: Boolean) {
        _decisionFlow.value = StreamingDecision(profile, score, immediateAction)
    }
}
