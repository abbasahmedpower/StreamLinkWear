package com.streamlink.shared

/**
 * @deprecated Renamed to [StreamQualityRuleEngine] to avoid collision with app-level decision engine.
 */
@Deprecated(
    message = "Use StreamQualityRuleEngine instead",
    replaceWith = ReplaceWith("StreamQualityRuleEngine", "com.streamlink.shared.StreamQualityRuleEngine")
)
typealias DecisionEngine = StreamQualityRuleEngine

class TrendAnalyzer(private val windowSize: Int = 5) {
    private val rttHistory  = ArrayDeque<Long>(windowSize)
    private val lossHistory = ArrayDeque<Double>(windowSize)

    fun record(rttMs: Long, lossRate: Double) {
        if (rttHistory.size  >= windowSize) rttHistory.removeFirst()
        if (lossHistory.size >= windowSize) lossHistory.removeFirst()
        rttHistory.addLast(rttMs)
        lossHistory.addLast(lossRate)
    }

    fun predictDegradationIn500ms(): Boolean {
        if (rttHistory.size < 3) return false
        val rttTrend  = rttHistory.last()  - rttHistory.first()
        val lossTrend = lossHistory.last() - lossHistory.first()
        return rttTrend > 50L || lossTrend > 0.05
    }

    fun currentRttAvg(): Long =
        if (rttHistory.isEmpty()) 0L else rttHistory.sum() / rttHistory.size

    fun isRisingRapidly(): Boolean {
        if (rttHistory.size < 2) return false
        val delta = rttHistory.last() - rttHistory[rttHistory.size - 2]
        return delta > 30L && rttHistory.last() > 80L
    }
}
