package com.streamlink.app.core.predictive

class PredictiveDecisionEvaluator(
    private val windowSize: Int = 10,
    private val rttDegradationSlopeThreshold: Float = 3.5f,
    private val requiredConfidence: Float = 0.6f
) {
    private val networkWindow = SlidingWindowBuffer(windowSize)
    private val workingArray = Array(windowSize) { NetworkSample(0, 0, 0, 0f, 0) }

    /**
     * Evaluates current metrics and returns a Risk Score (0.0 to 1.0)
     * Risk = 0.0 -> Safe
     * Risk = 1.0 -> Immediate action required
     */
    fun evaluateRisk(sample: NetworkSample, currentBattery: Int): Float {
        networkWindow.push(sample)
        val count = networkWindow.getOrderedValues(workingArray)
        
        val analysis = TrendPredictor.analyzeTrend(workingArray, count)

        // Ignore noisy data
        if (analysis.confidence < requiredConfidence) {
            return 0.0f
        }

        var riskScore = 0.0f

        // 1. RTT Slope Risk
        if (analysis.rttSlope > 0f) {
            // Mapping slope [0 to rttDegradationSlopeThreshold] -> Risk [0.0 to 0.5]
            val slopeRisk = (analysis.rttSlope / rttDegradationSlopeThreshold) * 0.5f
            riskScore += slopeRisk.coerceIn(0f, 0.5f)
        }

        // 2. High Jitter Risk
        if (analysis.rttJitter > 30f) {
            // Jitter > 30ms adds risk, up to 0.4
            val jitterRisk = ((analysis.rttJitter - 30f) / 100f) * 0.4f
            riskScore += jitterRisk.coerceIn(0f, 0.4f)
        }

        // 3. Battery Drain Velocity Check (Dropping too fast)
        if (currentBattery < 15) {
            // Critical battery adds a heavy baseline risk
            riskScore = maxOf(riskScore, 0.8f)
        }

        return riskScore.coerceIn(0.0f, 1.0f)
    }
}
