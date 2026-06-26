package com.streamlink.shared

import kotlin.math.abs

class DecisionEngine {
    fun decide(m: StreamMetrics): StreamAction = when {
        m.batteryLevel < 8                              -> StreamAction.PAUSE
        m.thermalLevel > 9                              -> StreamAction.PAUSE
        // ✅ FIX: use >= 0.20 (was >0.20 — missed boundary)
        m.packetLossRate >= 0.20 || m.rttMs > 400      -> StreamAction.RECONNECT
        m.packetLossRate > 0.10 || m.rttMs > 200       -> StreamAction.REDUCE_QUALITY
        m.thermalLevel > 7                              -> StreamAction.DROP_FPS
        // ✅ FIX: batteryLevel >= 20 (was >20 — off-by-one)
        m.packetLossRate < 0.02 && m.rttMs < 60 && m.batteryLevel >= 20 -> StreamAction.INCREASE_QUALITY
        m.isUserMoving && m.rttMs > 100                -> StreamAction.DROP_FPS
        else                                            -> StreamAction.STABLE
    }

    fun performanceCap(action: StreamAction): Int = when (action) {
        StreamAction.DROP_FPS -> StreamProtocol.WEAR_FPS_ECO
        StreamAction.PAUSE    -> 0
        else                  -> StreamProtocol.WEAR_FPS_FULL
    }
}

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
