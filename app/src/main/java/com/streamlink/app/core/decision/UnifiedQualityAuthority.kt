package com.streamlink.app.core.decision

import android.util.Log
import com.streamlink.app.capture.HardwareEncoder
import com.streamlink.app.core.predictive.NetworkSample
import com.streamlink.app.core.predictive.PredictiveDecisionEvaluator
import com.streamlink.shared.EncodingProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UnifiedQualityAuthority (Phase 3 of C-5 Fix)
 *
 * This class is the SINGLE source of truth for dynamic quality adjustments.
 * It merges the old DecisionEngine (bitrate) and AdaptiveResolutionController (resolution)
 * into one unified state machine that outputs a single EncodingProfile.
 *
 * All decisions funnel into HardwareEncoder.applyProfile() atomically.
 */
@Singleton
class UnifiedQualityAuthority @Inject constructor(
    private val hardwareEncoder: HardwareEncoder,
    private val predictiveEvaluator: PredictiveDecisionEvaluator
) {
    private val tag = "UnifiedQualityAuthority"

    private val _currentProfile = MutableStateFlow(EncodingProfile.full())
    val currentProfileFlow: StateFlow<EncodingProfile> = _currentProfile.asStateFlow()

    // Hysteresis for Upgrades
    private var consecutiveGoodScores = 0
    private val RECOVERY_CYCLES_REQUIRED = 6 // Require sustained stability before upgrading

    // Manual override state
    private var isManualOverrideActive = false
    private var manualProfile: EncodingProfile? = null

    /**
     * Called by TelemetryAggregator when a new snapshot is ready.
     */
    fun evaluate(snapshot: TelemetrySnapshot) {
        if (isManualOverrideActive) {
            // Do not auto-adjust if the user forced a specific quality via Settings
            return
        }

        var healthScore = HeuristicsEvaluator.calculateHealthScore(snapshot)

        // --- Predictive Risk Adjustment ---
        val sample = NetworkSample(
            timestamp = System.currentTimeMillis(),
            rtt = snapshot.rttMs,
            jitter = snapshot.jitterMs,
            packetLoss = snapshot.packetLossPercent,
            bitrate = snapshot.bitrateKbps
        )
        
        val riskScore = predictiveEvaluator.evaluateRisk(sample, snapshot.batteryPercent)
        if (riskScore > 0f) {
            healthScore -= (riskScore * 50f)
            healthScore = healthScore.coerceAtLeast(0f)
        }

        // --- Hardware Bottleneck Overrides ---
        // Force ECO if thermal is high or CPU is stressed, regardless of network health
        val isHardwareStressed = snapshot.thermalLevel >= 7 || snapshot.cpuLoad > 0.85f

        val currentProf = _currentProfile.value
        val targetProf = when {
            isHardwareStressed -> EncodingProfile.eco()
            healthScore >= 85f -> EncodingProfile.full()
            healthScore >= 70f -> EncodingProfile.fromStreamingMultiplier("HIGH", 0.75f, 60)
            healthScore >= 50f -> EncodingProfile.fromStreamingMultiplier("BALANCED", 0.50f, 30)
            else               -> EncodingProfile.fromStreamingMultiplier("SURVIVAL", 0.25f, 24)
        }

        // --- Hysteresis & Transitions ---
        val targetOrdinal = getProfileOrdinal(targetProf)
        val currentOrdinal = getProfileOrdinal(currentProf)

        if (targetOrdinal < currentOrdinal) {
            // DOWNGRADE: Apply IMMEDIATELY
            consecutiveGoodScores = 0
            applyTargetProfile(targetProf, "Auto-Downgrade (Score: $healthScore)")
        } else if (targetOrdinal > currentOrdinal) {
            // UPGRADE: Require sustained stability
            consecutiveGoodScores++
            if (consecutiveGoodScores >= RECOVERY_CYCLES_REQUIRED) {
                consecutiveGoodScores = 0
                applyTargetProfile(targetProf, "Auto-Upgrade (Score: $healthScore)")
            }
        } else {
            // STABLE
            consecutiveGoodScores = 0
        }
    }

    /**
     * Called when the user manually changes the quality mode from SettingsPrefs.
     */
    fun applyManualOverride(profile: EncodingProfile) {
        isManualOverrideActive = true
        manualProfile = profile
        consecutiveGoodScores = 0
        Log.i(tag, "Manual override activated: ${profile.label}")
        applyTargetProfile(profile, "Manual Override", force = true)
    }

    /**
     * Can be used to clear manual override and resume auto behavior.
     */
    fun clearManualOverride() {
        if (isManualOverrideActive) {
            isManualOverrideActive = false
            manualProfile = null
            Log.i(tag, "Manual override cleared, resuming auto-evaluation")
        }
    }

    /**
     * Called by IntelEngine or CMD_SET_BITRATE to adjust bitrate directly.
     * We wrap it into the current profile to keep state synchronized.
     */
    fun adjustBitrateOnly(kbps: Int, reason: String) {
        val updatedProfile = _currentProfile.value.copy(bitrateKbps = kbps)
        applyTargetProfile(updatedProfile, reason)
    }

    /**
     * Called by FuzzyEngine or legacy triggers to adjust resolution directly.
     */
    fun adjustResolutionOnly(width: Int, height: Int, fps: Int, reason: String) {
        val updatedProfile = _currentProfile.value.copy(width = width, height = height, fps = fps)
        applyTargetProfile(updatedProfile, reason)
    }

    /**
     * Called by HardwareActuator to apply a full action (resolution + bitrate) atomically.
     */
    fun adjustQuality(width: Int, height: Int, fps: Int, bitrateKbps: Int, reason: String) {
        val updatedProfile = _currentProfile.value.copy(
            width = width, height = height, fps = fps, bitrateKbps = bitrateKbps
        )
        applyTargetProfile(updatedProfile, reason)
    }

    private fun applyTargetProfile(profile: EncodingProfile, reason: String, force: Boolean = false) {
        _currentProfile.value = profile
        hardwareEncoder.applyProfile(profile, reason, force)
    }

    // Helper to rank profiles for hysteresis (Higher = Better Quality)
    private fun getProfileOrdinal(p: EncodingProfile): Int {
        if (p.width <= EncodingProfile.eco().width) return 0 // Survival/Eco
        return p.bitrateKbps // Simple fallback ranking for dynamic bitrates
    }
}
