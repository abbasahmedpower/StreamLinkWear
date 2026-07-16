package com.streamlink.shared.telemetry

import android.os.PowerManager
import com.streamlink.shared.ResolutionProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

data class StreamingControlAction(
    val targetBitrateKbps: Int,
    val targetFps: Int,
    val profile: ResolutionProfile,
    val requestKeyframe: Boolean,
    val reason: String
)

class FuzzyDecisionEngine(
    private val metricsCollector: MetricsCollector,
    private val scope: CoroutineScope
) {
    val controlActionsFlow: StateFlow<StreamingControlAction> by lazy {
        metricsCollector.metricsFlow
            .map { metrics -> evaluateMetrics(metrics) }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = StreamingControlAction(
                    targetBitrateKbps = 1500,
                    targetFps = 30,
                    profile = ResolutionProfile("FULL", 640, 480, 30, 1500),
                    requestKeyframe = false,
                    reason = "Initialization"
                )
            )
    }

    private fun evaluateMetrics(metrics: SystemMetricsState): StreamingControlAction {
        // --- 1. Fuzzification ---
        val congestion = metrics.network.queueCongestion

        // Calculate fuzzy membership for Network Congestion (Good, Medium, Bad)
        val netGood = fuzzyMembership(congestion, 0.0f, 0.0f, 0.2f, 0.4f)
        val netMedium = fuzzyMembership(congestion, 0.3f, 0.5f, 0.6f, 0.8f)
        val netBad = fuzzyMembership(congestion, 0.7f, 0.9f, 1.0f, 1.0f)

        // ✅ Fix: Thermal status mapping as a fuzzy continuum rather than binary switch
        val thermalValue = metrics.thermalStatus.toFloat() / PowerManager.THERMAL_STATUS_SHUTDOWN
        val thermalNormal = fuzzyMembership(thermalValue, 0.0f, 0.0f, 0.2f, 0.4f)
        val thermalHot = fuzzyMembership(thermalValue, 0.3f, 0.6f, 1.0f, 1.0f)

        // --- 2. Rule Evaluation (MIN operator for AND, MAX for OR) ---
        
        // Rule 1: Good Network AND Normal Thermal -> Max Performance
        val rule1_maxPerformance = minOf(netGood, thermalNormal)
        
        // Rule 2: Medium Network OR Hot Thermal -> Mild Throttle
        val rule2_mildThrottle = maxOf(netMedium, minOf(netGood, thermalHot))
        
        // Rule 3: Bad Network OR Critical Thermal -> Emergency Throttle
        val rule3_emergencyThrottle = maxOf(netBad, thermalHot)

        // --- 3. Defuzzification (Centroid Method) ---
        val sumOfWeights = rule1_maxPerformance + rule2_mildThrottle + rule3_emergencyThrottle
        
        val targetBitrate: Int
        val targetFps: Int
        val profile: ResolutionProfile
        val reason: String

        if (sumOfWeights > 0.01f) {
            targetBitrate = ((rule1_maxPerformance * 2000 + rule2_mildThrottle * 1000 + rule3_emergencyThrottle * 300) / sumOfWeights).toInt()
            targetFps = ((rule1_maxPerformance * 30 + rule2_mildThrottle * 24 + rule3_emergencyThrottle * 15) / sumOfWeights).toInt()
            
            // Resolution fallback based on severity
            profile = if (rule3_emergencyThrottle > 0.6f) {
                ResolutionProfile("ECO", 320, 240, targetFps, targetBitrate) // ECO Profile
            } else {
                ResolutionProfile("FULL", 640, 480, targetFps, targetBitrate) // FULL Profile
            }

            reason = when {
                rule3_emergencyThrottle > 0.5f -> "Emergency Throttling (Weight: ${"%.2f".format(rule3_emergencyThrottle)})"
                rule2_mildThrottle > 0.5f -> "Optimizing Stream Quality (Weight: ${"%.2f".format(rule2_mildThrottle)})"
                else -> "Running at Peak Performance"
            }
        } else {
            // Safe fallback
            targetBitrate = 1500
            targetFps = 30
            profile = ResolutionProfile("FULL", 640, 480, 30, 1500)
            reason = "Default System State"
        }

        // ✅ Fix: Instantly request keyframe based on raw delta (bypassing EMA) to fix broken decoders rapidly
        val requestKeyframe = metrics.network.droppedFramesDelta > 5

        return StreamingControlAction(
            targetBitrateKbps = targetBitrate,
            targetFps = targetFps,
            profile = profile,
            requestKeyframe = requestKeyframe,
            reason = reason
        )
    }

    /**
     * Trapezoidal Membership Function
     * ✅ Fix: Prevents division by zero (NaN) using EPSILON padding if bounds match.
     */
    private fun fuzzyMembership(x: Float, a: Float, b: Float, c: Float, d: Float): Float {
        val epsilon = 1e-5f
        return when {
            x <= a -> 0.0f
            x > a && x < b -> (x - a) / maxOf((b - a), epsilon)
            x >= b && x <= c -> 1.0f
            x > c && x < d -> (d - x) / maxOf((d - c), epsilon)
            else -> 0.0f
        }
    }
}
