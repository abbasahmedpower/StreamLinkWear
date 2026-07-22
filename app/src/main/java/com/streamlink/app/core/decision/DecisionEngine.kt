package com.streamlink.app.core.decision

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DecisionEngine {

    private val _currentDecision = MutableStateFlow(
        StreamingDecision(StreamingProfile.ULTRA, 100f, false)
    )
    val decisionFlow: StateFlow<StreamingDecision> = _currentDecision.asStateFlow()

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
            jitter = 0, // In full implementation, jitter would be passed down from Telemetry
            packetLoss = snapshot.packetLossPercent,
            bitrate = 0
        )
        
        val riskScore = predictiveEvaluator.evaluateRisk(sample, snapshot.batteryPercent)
        
        // Apply Risk Score to Health Score (Proactive adjustment)
        if (riskScore > 0f) {
            // A risk of 1.0 means we subtract up to 50 points from the health score
            healthScore -= (riskScore * 50f)
            healthScore = healthScore.coerceAtLeast(0f)
        }

        val currentProfile = _currentDecision.value.targetProfile
        
        // Map score to target profile
        val targetProfile = when {
            healthScore >= 85f -> StreamingProfile.ULTRA
            healthScore >= 70f -> StreamingProfile.HIGH
            healthScore >= 50f -> StreamingProfile.BALANCED
            else -> StreamingProfile.SURVIVAL
        }

        // --- Hysteresis Logic (Micro-level optimization) ---
        
        if (targetProfile.ordinal > currentProfile.ordinal) {
            // DOWNGRADE (e.g., ULTRA -> HIGH): Apply IMMEDIATELY (Fast-Drop)
            consecutiveGoodScores = 0
            emitDecision(targetProfile, healthScore, immediateAction = true)
            
        } else if (targetProfile.ordinal < currentProfile.ordinal) {
            // UPGRADE (e.g., HIGH -> ULTRA): Require sustained stability (Slow-Recovery)
            consecutiveGoodScores++
            if (consecutiveGoodScores >= RECOVERY_CYCLES_REQUIRED) {
                consecutiveGoodScores = 0 // Reset after upgrade
                emitDecision(targetProfile, healthScore, immediateAction = false)
            }
        } else {
            // STABLE: Score changed, but profile remains the same
            consecutiveGoodScores = 0
            // Optionally update the score without triggering a profile change
            if (Math.abs(_currentDecision.value.healthScore - healthScore) > 2f) {
                emitDecision(currentProfile, healthScore, immediateAction = false)
            }
        }
    }

    private fun emitDecision(profile: StreamingProfile, score: Float, immediateAction: Boolean) {
        _currentDecision.value = StreamingDecision(profile, score, immediateAction)
    }
}
