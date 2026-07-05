package com.streamlink.shared

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.absoluteValue
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * StreamingIntelligenceEngine — 7-Layer Adaptive Streaming QoS Engine.
 *
 * Replaces the simple QualityController with a production-grade system inspired
 * by Netflix ABR and commercial streaming engines:
 *
 * Layer 1: Raw Metrics Collection (every 200ms)
 * Layer 2: Sliding Window Statistics (Mean, Median, P95, StdDev)
 * Layer 3: Spike Detection (transient vs sustained degradation)
 * Layer 4: Dynamic Weighted Scoring (context-aware weights)
 * Layer 5: Hysteresis (dual-threshold to prevent oscillation)
 * Layer 6: Rate Limiter (max 1 quality change per N seconds)
 * Layer 7: Prediction Stub (future ML integration point)
 */
class StreamingIntelligenceEngine(
    private val scope: CoroutineScope,
    private val onBitrateChange: (kbps: Int) -> Unit = {},
    private val onFpsChange: (fps: Int) -> Unit = {},
    private val onResolutionChange: (scale: Float) -> Unit = {},
    private val onIFrameIntervalChange: (seconds: Int) -> Unit = {}
) {
    companion object {
        private const val TAG = "StreamIntel"
        
        // Quality tiers
        const val TIER_ULTRA = 4
        const val TIER_HIGH = 3
        const val TIER_MEDIUM = 2
        const val TIER_LOW = 1
        const val TIER_SURVIVAL = 0

        // Rate limiter
        private const val MIN_CHANGE_INTERVAL_MS = 5000L  // 5 seconds between changes
        private const val EMERGENCY_OVERRIDE_SCORE = 0.15f // Below this = immediate action

        // Hysteresis thresholds
        private const val UPGRADE_THRESHOLD = 0.75f
        private const val DOWNGRADE_THRESHOLD = 0.45f
    }

    // ─── Quality Profile ────────────────────────────────────────────────
    data class QualityProfile(
        val tier: Int,
        val bitrateKbps: Int,
        val fps: Int,
        val resolutionScale: Float,
        val iFrameIntervalSec: Int
    )

    private val profiles = mapOf(
        TIER_ULTRA to QualityProfile(TIER_ULTRA, StreamProtocol.WEAR_BPS_FULL, StreamProtocol.WEAR_FPS_FULL, 1.0f, 3),
        TIER_HIGH to QualityProfile(TIER_HIGH, 1400, 30, 1.0f, 2),
        TIER_MEDIUM to QualityProfile(TIER_MEDIUM, 900, 24, 0.85f, 2),
        TIER_LOW to QualityProfile(TIER_LOW, 500, 20, 0.75f, 1),
        TIER_SURVIVAL to QualityProfile(TIER_SURVIVAL, 300, 15, 0.50f, 1)
    )

    private val _currentProfile = MutableStateFlow(profiles[TIER_ULTRA]!!)
    val currentProfile: StateFlow<QualityProfile> = _currentProfile

    // Raw Metric Inputs (updated externally)
    @Volatile var currentRttMs: Long = 0L
    @Volatile var packetLossRate: Float = 0f
    @Volatile var jitterMs: Long = 0L
    @Volatile var thermalLevel: Int = 0       // 0–10
    @Volatile var batteryLevel: Int = 100     // 0–100
    @Volatile var cpuUsagePercent: Float = 0f
    @Volatile var decoderQueueSize: Int = 0
    @Volatile var currentFps: Int = 0
    @Volatile var networkBandwidthKbps: Int = 10_000
    @Volatile var thermalCeilingKbps: Int = StreamProtocol.WEAR_BPS_FULL

    // Compatibility method
    fun downgradeQuality() {
        forceDowngrade()
    }

    // ─── Layer 2: Sliding Windows ───────────────────────────────────────
    private val rttWindow = SlidingWindow(capacity = 20)    // ~2s at 10Hz
    private val lossWindow = SlidingWindow(capacity = 30)   // ~3s
    private val jitterWindow = SlidingWindow(capacity = 20)  // ~2s
    private val fpsWindow = SlidingWindow(capacity = 10)     // ~1s
    private val thermalWindow = SlidingWindow(capacity = 50) // ~5s
    private val cpuWindow = SlidingWindow(capacity = 50)     // ~5s

    // ─── Layer 3: Spike Detection ───────────────────────────────────────
    private var consecutiveBadSamples = 0
    private val SPIKE_THRESHOLD = 3 // Need 3+ consecutive bad samples to act

    // ─── Layer 6: Rate Limiter ──────────────────────────────────────────
    private val lastQualityChangeMs = AtomicLong(0L)

    private var monitorJob: Job? = null

    // ═══════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════

    fun start() {
        monitorJob?.cancel()
        monitorJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(200) // 5Hz evaluation
                collectAndEvaluate()
            }
        }
        Log.i(TAG, "StreamingIntelligenceEngine started (7-layer)")
    }

    fun stop() {
        monitorJob?.cancel()
        Log.i(TAG, "StreamingIntelligenceEngine stopped")
    }

    /** Force an immediate downgrade (e.g., from external backpressure signal) */
    fun forceDowngrade() {
        val current = _currentProfile.value
        val nextTier = (current.tier - 1).coerceAtLeast(TIER_SURVIVAL)
        applyTier(nextTier, force = true)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Core Evaluation Pipeline
    // ═══════════════════════════════════════════════════════════════════

    private fun collectAndEvaluate() {
        // ── Layer 1: Collect raw metrics into windows ──
        rttWindow.add(currentRttMs.toFloat())
        lossWindow.add(packetLossRate * 100f) // store as percentage
        jitterWindow.add(jitterMs.toFloat())
        fpsWindow.add(currentFps.toFloat())
        thermalWindow.add(thermalLevel.toFloat())
        cpuWindow.add(cpuUsagePercent)

        // ── Layer 2: Compute statistics ──
        val rttStats = rttWindow.stats()
        val lossStats = lossWindow.stats()
        val jitterStats = jitterWindow.stats()
        val thermalStats = thermalWindow.stats()

        // ── Layer 3: Spike detection ──
        val isBadSample = rttStats.p95 > 200f || lossStats.median > 5f || jitterStats.p95 > 100f
        if (isBadSample) {
            consecutiveBadSamples++
        } else {
            consecutiveBadSamples = 0
        }
        val isSustainedDegradation = consecutiveBadSamples >= SPIKE_THRESHOLD

        // ── Layer 4: Dynamic weighted score ──
        val score = computeWeightedScore(rttStats, lossStats, jitterStats, thermalStats)

        // ── Layer 5 & 6: Hysteresis + Rate limiting ──
        val currentTier = _currentProfile.value.tier
        val now = System.currentTimeMillis()
        val timeSinceLastChange = now - lastQualityChangeMs.get()

        // Emergency override — skip rate limiter
        if (score < EMERGENCY_OVERRIDE_SCORE || batteryLevel < 8 || thermalLevel >= 9) {
            val emergencyTier = when {
                batteryLevel < 5 || thermalLevel >= 10 -> TIER_SURVIVAL
                score < 0.10f -> TIER_SURVIVAL
                score < EMERGENCY_OVERRIDE_SCORE -> TIER_LOW
                else -> (currentTier - 1).coerceAtLeast(TIER_SURVIVAL)
            }
            if (emergencyTier < currentTier) {
                applyTier(emergencyTier, force = true)
            }
            return
        }

        // Rate limiter — no changes within cooldown period
        if (timeSinceLastChange < MIN_CHANGE_INTERVAL_MS) return

        // Hysteresis — dual threshold
        val targetTier = when {
            // Upgrade path: need score ABOVE upgrade threshold AND sustained good conditions
            score > UPGRADE_THRESHOLD && !isSustainedDegradation && consecutiveBadSamples == 0 ->
                (currentTier + 1).coerceAtMost(TIER_ULTRA)
            // Downgrade path: score BELOW downgrade threshold AND sustained degradation
            score < DOWNGRADE_THRESHOLD && isSustainedDegradation ->
                (currentTier - 1).coerceAtLeast(TIER_SURVIVAL)
            else -> currentTier // No change — in hysteresis dead zone
        }

        if (targetTier != currentTier) {
            applyTier(targetTier)
        }
    }

    // ─── Layer 4: Dynamic Weighted Scoring ──────────────────────────────
    private fun computeWeightedScore(
        rtt: WindowStats,
        loss: WindowStats,
        jitter: WindowStats,
        thermal: WindowStats
    ): Float {
        // Base scores (0.0 = terrible, 1.0 = perfect)
        val rttScore = when {
            rtt.median < 40f -> 1.0f
            rtt.median < 80f -> 0.85f
            rtt.median < 150f -> 0.6f
            rtt.median < 300f -> 0.3f
            else -> 0.1f
        }
        val lossScore = when {
            loss.median < 1f -> 1.0f
            loss.median < 3f -> 0.8f
            loss.median < 8f -> 0.5f
            loss.median < 15f -> 0.2f
            else -> 0.05f
        }
        val jitterScore = when {
            jitter.median < 20f -> 1.0f
            jitter.median < 50f -> 0.8f
            jitter.median < 100f -> 0.5f
            jitter.median < 200f -> 0.2f
            else -> 0.05f
        }
        val thermalScore = when {
            thermal.mean < 3f -> 1.0f
            thermal.mean < 5f -> 0.8f
            thermal.mean < 7f -> 0.5f
            thermal.mean < 9f -> 0.2f
            else -> 0.0f
        }
        val batteryScore = when {
            batteryLevel > 50 -> 1.0f
            batteryLevel > 30 -> 0.85f
            batteryLevel > 15 -> 0.6f
            batteryLevel > 8 -> 0.3f
            else -> 0.1f
        }

        // Dynamic weights — context-aware
        val isNetworkStressed = lossScore < 0.5f || rttScore < 0.5f
        val isThermalStressed = thermalScore < 0.5f
        val isBatteryLow = batteryScore < 0.5f

        val wRtt: Float
        val wLoss: Float
        val wJitter: Float
        val wThermal: Float
        val wBattery: Float

        when {
            isNetworkStressed -> {
                wRtt = 0.30f; wLoss = 0.35f; wJitter = 0.15f; wThermal = 0.10f; wBattery = 0.10f
            }
            isThermalStressed -> {
                wRtt = 0.15f; wLoss = 0.15f; wJitter = 0.10f; wThermal = 0.45f; wBattery = 0.15f
            }
            isBatteryLow -> {
                wRtt = 0.15f; wLoss = 0.15f; wJitter = 0.10f; wThermal = 0.15f; wBattery = 0.45f
            }
            else -> {
                wRtt = 0.25f; wLoss = 0.25f; wJitter = 0.15f; wThermal = 0.20f; wBattery = 0.15f
            }
        }

        return rttScore * wRtt + lossScore * wLoss + jitterScore * wJitter +
                thermalScore * wThermal + batteryScore * wBattery
    }

    // ─── Apply Quality Tier ─────────────────────────────────────────────
    private fun applyTier(tier: Int, force: Boolean = false) {
        val newProfile = profiles[tier] ?: return
        val old = _currentProfile.value
        if (old.tier == tier && !force) return

        _currentProfile.value = newProfile
        lastQualityChangeMs.set(System.currentTimeMillis())

        Log.i(TAG, "Quality: Tier ${old.tier} → $tier | " +
                "${newProfile.bitrateKbps}kbps ${newProfile.fps}fps " +
                "scale=${newProfile.resolutionScale} iFrame=${newProfile.iFrameIntervalSec}s")

        if (old.bitrateKbps != newProfile.bitrateKbps) onBitrateChange(newProfile.bitrateKbps)
        if (old.fps != newProfile.fps) onFpsChange(newProfile.fps)
        if (old.resolutionScale != newProfile.resolutionScale) onResolutionChange(newProfile.resolutionScale)
        if (old.iFrameIntervalSec != newProfile.iFrameIntervalSec) onIFrameIntervalChange(newProfile.iFrameIntervalSec)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Layer 2: Sliding Window Implementation
    // ═══════════════════════════════════════════════════════════════════

    data class WindowStats(
        val mean: Float,
        val median: Float,
        val min: Float,
        val max: Float,
        val stdDev: Float,
        val p95: Float
    )

    class SlidingWindow(private val capacity: Int) {
        private val buffer = FloatArray(capacity)
        private var head = 0
        private var count = 0

        @Synchronized
        fun add(value: Float) {
            buffer[head] = value
            head = (head + 1) % capacity
            if (count < capacity) count++
        }

        @Synchronized
        fun stats(): WindowStats {
            if (count == 0) return WindowStats(0f, 0f, 0f, 0f, 0f, 0f)

            val data = FloatArray(count)
            for (i in 0 until count) {
                data[i] = buffer[((head - count + i + capacity) % capacity)]
            }
            data.sort()

            val sum = data.sum()
            val mean = sum / count
            val median = if (count % 2 == 0) {
                (data[count / 2 - 1] + data[count / 2]) / 2f
            } else {
                data[count / 2]
            }
            val min = data[0]
            val max = data[count - 1]

            var varianceSum = 0f
            for (v in data) {
                val diff = v - mean
                varianceSum += diff * diff
            }
            val stdDev = sqrt(varianceSum / count)

            val p95Index = ((count - 1) * 0.95f).toInt().coerceIn(0, count - 1)
            val p95 = data[p95Index]

            return WindowStats(mean, median, min, max, stdDev, p95)
        }

        @Synchronized
        fun clear() {
            head = 0
            count = 0
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Layer 7: Prediction Stub (Future ML Integration)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Prediction interface for future ML model integration.
     * Currently returns null (no prediction), falling back to rule-based logic.
     */
    interface StreamPredictor {
        /** Returns predicted optimal tier, or null if no prediction available */
        fun predictOptimalTier(
            rttStats: WindowStats,
            lossStats: WindowStats,
            jitterStats: WindowStats,
            batteryLevel: Int,
            thermalLevel: Int
        ): Int?
    }

    /** Set a predictor to enable Layer 7. Null = disabled (default). */
    var predictor: StreamPredictor? = null
}
