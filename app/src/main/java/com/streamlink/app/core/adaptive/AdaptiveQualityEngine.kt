package com.streamlink.app.core.adaptive

import android.util.Log
import com.streamlink.app.capture.HardwareEncoder
import com.streamlink.app.core.decision.StreamingDecision
import com.streamlink.app.core.decision.StreamingProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AdaptiveQualityEngine(
    private val hardwareEncoder: HardwareEncoder,
    private val baseBitrateBps: Int,
    private val decisionFlow: StateFlow<StreamingDecision>
) {
    private var currentProfile: StreamingProfile? = null
    
    // Cooldown state
    private var lastTransitionTimeMs = 0L
    private val COOLDOWN_MS = 2000L // 2 seconds

    fun startListening(scope: CoroutineScope) {
        scope.launch {
            decisionFlow.collect { decision ->
                applyDecision(decision)
            }
        }
    }

    private fun applyDecision(decision: StreamingDecision) {
        val targetProfile = decision.targetProfile

        // 1. State-Diffing: Ignore if the profile hasn't changed
        if (currentProfile == targetProfile) return

        val now = System.currentTimeMillis()

        // 2. Cooldown check: Prevent flapping, except for severe downgrades
        val isDowngrade = currentProfile != null && targetProfile.ordinal > currentProfile!!.ordinal
        val isEmergency = decision.immediateActionRequired || isSevereDowngrade(currentProfile, targetProfile)
        
        if (!isEmergency && (now - lastTransitionTimeMs) < COOLDOWN_MS) {
            return // Still in cooldown, ignore non-emergency transitions
        }

        Log.i("AdaptiveEngine", "Transitioning Profile: $currentProfile -> $targetProfile (Score: ${decision.healthScore})")

        lastTransitionTimeMs = now

        // 3. Calculate the exact new bitrate
        val newBitrate = (baseBitrateBps * targetProfile.bitrateMultiplier).toInt()

        // 3. Apply Bitrate using the HardwareEncoder which has pre-allocated bundles
        hardwareEncoder.setBitrate(newBitrate / 1000)

        // 4. Artifact Flushing: Force IDR Keyframe on severe downgrades
        if (isSevereDowngrade(currentProfile, targetProfile) || decision.immediateActionRequired) {
            hardwareEncoder.forceKeyframe()
            Log.d("AdaptiveEngine", "Injected IDR Keyframe to prevent artifacts.")
        }

        currentProfile = targetProfile
    }

    /**
     * Determines if the jump downwards is severe enough to cause prediction artifacts.
     */
    private fun isSevereDowngrade(old: StreamingProfile?, new: StreamingProfile): Boolean {
        if (old == null) return false
        // E.g., Jumping from ULTRA to BALANCED or SURVIVAL (Difference of 2 or more levels)
        return (new.ordinal - old.ordinal) >= 2
    }
}
