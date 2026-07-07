package com.streamlink.shared.diagnostics

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

/**
 * Local Analytics Engine for tracking granular percentiles and occurrences without 
 * relying on external spy servers. Critical for Validation Phase 18 (Soak Tests).
 */
class LocalAnalyticsEngine {
    
    private val fpsHistory = CopyOnWriteArrayList<Int>()
    private val latencyHistory = CopyOnWriteArrayList<Long>()
    
    // Codec Failures Tracking
    private val codecFailures = CopyOnWriteArrayList<CodecFailureEvent>()
    
    // Reconnect Tracking
    private val reconnectEvents = CopyOnWriteArrayList<ReconnectEvent>()
    
    fun recordFps(fps: Int) {
        fpsHistory.add(fps)
        // Keep bounded for long soak tests
        if (fpsHistory.size > 100_000) fpsHistory.removeAt(0)
    }
    
    fun recordLatency(latencyMs: Long) {
        latencyHistory.add(latencyMs)
        // Keep bounded for long soak tests
        if (latencyHistory.size > 100_000) latencyHistory.removeAt(0)
    }
    
    fun recordCodecFailure(codecName: String, exceptionName: String, recoveryTimeMs: Long, success: Boolean) {
        codecFailures.add(CodecFailureEvent(codecName, exceptionName, recoveryTimeMs, success))
    }
    
    fun recordReconnect(reason: ReconnectReason) {
        reconnectEvents.add(ReconnectEvent(reason, System.currentTimeMillis()))
    }
    
    fun generateAnalyticsReport(): AnalyticsReport {
        val sortedFps = fpsHistory.sorted()
        val sortedLatency = latencyHistory.sorted()
        
        return AnalyticsReport(
            fpsStats = calculateIntStats(sortedFps),
            latencyStats = calculateLongStats(sortedLatency),
            codecFailuresCount = codecFailures.size,
            reconnectsCount = reconnectEvents.size,
            reconnectBreakdown = getReconnectBreakdown(),
            codecFailureBreakdown = getCodecFailureBreakdown()
        )
    }
    
    private fun calculateIntStats(sortedList: List<Int>): PercentileStats<Int> {
        if (sortedList.isEmpty()) return PercentileStats(0, 0, 0, 0, 0, 0, 0)
        
        val avg = sortedList.average().roundToInt()
        return PercentileStats(
            average = avg,
            min = sortedList.first(),
            max = sortedList.last(),
            p50 = getPercentile(sortedList, 50.0),
            p90 = getPercentile(sortedList, 90.0),
            p95 = getPercentile(sortedList, 95.0),
            p99 = getPercentile(sortedList, 99.0)
        )
    }
    
    private fun calculateLongStats(sortedList: List<Long>): PercentileStats<Long> {
        if (sortedList.isEmpty()) return PercentileStats(0L, 0L, 0L, 0L, 0L, 0L, 0L)
        
        val avg = sortedList.average().toLong()
        return PercentileStats(
            average = avg,
            min = sortedList.first(),
            max = sortedList.last(),
            p50 = getPercentile(sortedList, 50.0),
            p90 = getPercentile(sortedList, 90.0),
            p95 = getPercentile(sortedList, 95.0),
            p99 = getPercentile(sortedList, 99.0)
        )
    }
    
    private inline fun <reified T : Number> getPercentile(sortedList: List<T>, percentile: Double): T {
        val index = Math.ceil((percentile / 100.0) * sortedList.size).toInt() - 1
        return sortedList[maxOf(0, index)]
    }
    
    private fun getReconnectBreakdown(): Map<ReconnectReason, Int> {
        return reconnectEvents.groupingBy { it.reason }.eachCount()
    }
    
    private fun getCodecFailureBreakdown(): List<CodecFailureEvent> {
        return codecFailures.toList()
    }
}

data class PercentileStats<T>(
    val average: T,
    val min: T,
    val max: T,
    val p50: T,
    val p90: T,
    val p95: T,
    val p99: T
)

data class CodecFailureEvent(
    val codecName: String,
    val exceptionName: String,
    val recoveryTimeMs: Long,
    val success: Boolean
)

data class ReconnectEvent(
    val reason: ReconnectReason,
    val timestampMs: Long
)

enum class ReconnectReason {
    WIFI_LOST,
    PEER_LOST,
    SERVER_LOST,
    BLUETOOTH_LOST,
    ADB_LOST,
    TIMEOUT,
    ENCRYPTION_FAILED
}

data class AnalyticsReport(
    val fpsStats: PercentileStats<Int>,
    val latencyStats: PercentileStats<Long>,
    val codecFailuresCount: Int,
    val reconnectsCount: Int,
    val reconnectBreakdown: Map<ReconnectReason, Int>,
    val codecFailureBreakdown: List<CodecFailureEvent>
)
