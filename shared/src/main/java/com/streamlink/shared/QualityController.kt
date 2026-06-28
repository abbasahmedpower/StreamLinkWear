package com.streamlink.shared

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.absoluteValue

/**
 * QualityController — Real-time QoS engine.
 *
 * Controls:
 * - Target bitrate (driven by backpressure + thermal + battery)
 * - Target FPS (driven by movement + thermal)
 * - Resolution scale (driven by thermal + severe congestion)
 * - I-frame interval (driven by packet loss rate)
 *
 * Design: polls metrics every 200ms, drives decisions in one place.
 */
class QualityController(
    private val scope: CoroutineScope,
    private val onBitrateChange: (kbps: Int) -> Unit    = {},
    private val onFpsChange: (fps: Int) -> Unit          = {},
    private val onResolutionChange: (scale: Float) -> Unit = {},
    private val onIFrameIntervalChange: (seconds: Int) -> Unit = {}
) {
    private val tag = "QoSEngine"

    data class QualityProfile(
        val bitrateKbps: Int,
        val fps: Int,
        val resolutionScale: Float,   // 1.0 = full, 0.75 = 75%, 0.5 = half
        val iFrameIntervalSec: Int
    )

    private val _profile = MutableStateFlow(
        QualityProfile(
            bitrateKbps = StreamProtocol.WEAR_BPS_FULL,
            fps = StreamProtocol.WEAR_FPS_FULL,
            resolutionScale = 1.0f,
            iFrameIntervalSec = 1
        )
    )
    val profile: StateFlow<QualityProfile> = _profile

    // Input signals (updated externally)
    @Volatile var currentRttMs: Long = 0L
    @Volatile var packetLossRate: Float = 0f
    @Volatile var thermalLevel: Int = 0      // 0–10
    @Volatile var batteryLevel: Int = 100    // 0–100
    @Volatile var isUserMoving: Boolean = false
    @Volatile var networkBandwidthKbps: Int = 10_000
    @Volatile var thermalCeilingKbps: Int = StreamProtocol.WEAR_BPS_FULL

    private var monitorJob: Job? = null
    private val lastBitrateChangeMs = AtomicLong(0L)
    private val BITRATE_CHANGE_COOLDOWN_MS = 300L

    fun start() {
        monitorJob?.cancel()
        monitorJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(200)
                evaluate()
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
    }

    fun downgradeQuality() {
        packetLossRate = packetLossRate.coerceAtLeast(0.25f)
        currentRttMs = currentRttMs.coerceAtLeast(500L)
        evaluate()
    }

    private fun evaluate() {
        val current = _profile.value

        // ── Bitrate ──────────────────────────────────────────────────────
        val targetBitrate = computeTargetBitrate(current.bitrateKbps)

        // ── FPS ──────────────────────────────────────────────────────────
        val targetFps = computeTargetFps()

        // ── Resolution ───────────────────────────────────────────────────
        val targetScale = computeResolutionScale()

        // ── I-Frame Interval ─────────────────────────────────────────────
        val targetIFrameInterval = computeIFrameInterval()

        // Apply only changed values
        val now = System.currentTimeMillis()
        val bitrateChanged = (targetBitrate - current.bitrateKbps).absoluteValue >= 50
        val fpsChanged     = targetFps != current.fps
        val scaleChanged   = (targetScale - current.resolutionScale).absoluteValue >= 0.01f
        val iFrameChanged  = targetIFrameInterval != current.iFrameIntervalSec

        if (!bitrateChanged && !fpsChanged && !scaleChanged && !iFrameChanged) return

        // Throttle rapid bitrate changes
        if (bitrateChanged && now - lastBitrateChangeMs.get() < BITRATE_CHANGE_COOLDOWN_MS) return

        val newProfile = current.copy(
            bitrateKbps      = if (bitrateChanged) targetBitrate else current.bitrateKbps,
            fps              = if (fpsChanged)     targetFps     else current.fps,
            resolutionScale  = if (scaleChanged)   targetScale   else current.resolutionScale,
            iFrameIntervalSec = if (iFrameChanged) targetIFrameInterval else current.iFrameIntervalSec
        )
        _profile.value = newProfile

        if (bitrateChanged) {
            lastBitrateChangeMs.set(now)
            onBitrateChange(targetBitrate)
            Log.d(tag, "Bitrate ${current.bitrateKbps} → ${targetBitrate}kbps")
        }
        if (fpsChanged)    onFpsChange(targetFps)
        if (scaleChanged)  onResolutionChange(targetScale)
        if (iFrameChanged) onIFrameIntervalChange(targetIFrameInterval)
    }

    private fun computeTargetBitrate(currentKbps: Int): Int {
        val ceiling = minOf(thermalCeilingKbps, networkBandwidthKbps / 2)

        return when {
            batteryLevel < 10  -> 300
            thermalLevel >= 9  -> 300
            thermalLevel >= 7  -> currentKbps.coerceAtMost(800)
            packetLossRate >= 0.20f || currentRttMs > 400 ->
                (currentKbps * 0.60).toInt().coerceAtLeast(300)
            packetLossRate > 0.05f || currentRttMs > 150 ->
                (currentKbps * 0.85).toInt().coerceAtLeast(300)
            packetLossRate < 0.02f && currentRttMs < 60 ->
                (currentKbps + 150).coerceAtMost(ceiling)
            else -> currentKbps
        }.coerceIn(300, ceiling.coerceAtLeast(300))
    }

    private fun computeTargetFps(): Int = when {
        thermalLevel >= 9       -> 15
        thermalLevel >= 7       -> 20
        isUserMoving && currentRttMs > 100 -> StreamProtocol.WEAR_FPS_ECO
        batteryLevel < 15       -> 20
        else                    -> StreamProtocol.WEAR_FPS_FULL
    }

    private fun computeResolutionScale(): Float = when {
        thermalLevel >= 9                       -> 0.50f
        thermalLevel >= 8                       -> 0.75f
        packetLossRate >= 0.20f                 -> 0.75f
        packetLossRate < 0.02f && thermalLevel < 5 -> 1.00f
        else                                    -> _profile.value.resolutionScale
    }

    private fun computeIFrameInterval(): Int = when {
        packetLossRate >= 0.10f -> 1   // Keyframe every second (fast recovery)
        packetLossRate >= 0.05f -> 2
        else                    -> 3   // Normal: keyframe every 3s
    }
}
