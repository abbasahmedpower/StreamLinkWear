package com.streamlink.app.core.predictive

import kotlin.math.sqrt

data class TrendAnalysis(
    val rttSlope: Float,
    val rttVariance: Float,
    val rttJitter: Float,
    val confidence: Float
)

object TrendPredictor {

    /**
     * Calculates the slope, variance, jitter and confidence score for a series of NetworkSamples.
     */
    fun analyzeTrend(samples: Array<NetworkSample>, count: Int): TrendAnalysis {
        if (count < 2) return TrendAnalysis(0f, 0f, 0f, 0f)

        var sumX = 0f
        var sumY = 0f
        var sumXY = 0f
        var sumX2 = 0f

        var maxRtt = 0
        var minRtt = Int.MAX_VALUE
        var sumJitter = 0f
        var lastRtt = samples[0].rtt

        for (i in 0 until count) {
            val x = i.toFloat()
            val y = samples[i].rtt.toFloat()
            sumX += x
            sumY += y
            sumXY += x * y
            sumX2 += x * x

            if (samples[i].rtt > maxRtt) maxRtt = samples[i].rtt
            if (samples[i].rtt < minRtt) minRtt = samples[i].rtt
            
            if (i > 0) {
                sumJitter += Math.abs(samples[i].rtt - lastRtt).toFloat()
            }
            lastRtt = samples[i].rtt
        }

        val n = count.toFloat()
        val meanY = sumY / n
        
        // Slope calculation
        val numerator = (n * sumXY) - (sumX * sumY)
        val denominator = (n * sumX2) - (sumX * sumX)
        val slope = if (denominator == 0f) 0f else numerator / denominator

        // Variance calculation
        var sumVariance = 0f
        for (i in 0 until count) {
            val diff = samples[i].rtt.toFloat() - meanY
            sumVariance += diff * diff
        }
        val variance = sumVariance / n
        
        // Jitter average
        val avgJitter = sumJitter / (count - 1).coerceAtLeast(1)

        // Confidence calculation (0.0 to 1.0)
        // High count, low unexplainable variance -> high confidence.
        // We use the count to weight confidence, heavily penalized if count < 5
        val countConfidence = (count / 10f).coerceIn(0f, 1f)
        
        // Standard deviation
        val stdDev = sqrt(variance.toDouble()).toFloat()
        
        // If stdDev is massive compared to the mean, confidence drops.
        val stabilityConfidence = if (meanY > 0) {
            (1f - (stdDev / meanY)).coerceIn(0f, 1f)
        } else {
            1f
        }

        val finalConfidence = countConfidence * 0.4f + stabilityConfidence * 0.6f

        return TrendAnalysis(
            rttSlope = slope,
            rttVariance = variance,
            rttJitter = avgJitter,
            confidence = finalConfidence
        )
    }
}
