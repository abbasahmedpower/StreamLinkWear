package com.streamlink.shared

/**
 * Fast, deterministic rule-based engine for hot-path streaming quality decisions.
 * Renamed from DecisionEngine to avoid collision with predictive app-level decision engine.
 */
class StreamQualityRuleEngine {

    fun decide(m: StreamMetrics): StreamAction = when {
        m.batteryLevel < 8                              -> StreamAction.PAUSE
        m.thermalLevel > 9                              -> StreamAction.PAUSE
        m.packetLossRate >= 0.20 || m.rttMs > 400      -> StreamAction.RECONNECT
        m.packetLossRate > 0.10 || m.rttMs > 200       -> StreamAction.REDUCE_QUALITY
        m.thermalLevel > 7                              -> StreamAction.DROP_FPS
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
