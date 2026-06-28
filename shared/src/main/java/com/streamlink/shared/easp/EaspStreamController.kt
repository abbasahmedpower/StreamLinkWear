package com.streamlink.shared.easp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class StreamProfile(
    val width: Int,
    val height: Int,
    val maxFps: Int,
    val targetBitrateKbps: Int,
    val gopSize: Int // Increase GOP size during static states to save packets
)

class EaspStreamController {

    private val _currentProfile = MutableStateFlow(getProfileForState(WristState.PASSIVE))
    val currentProfile: StateFlow<StreamProfile> = _currentProfile.asStateFlow()

    private var lastKnownState = WristState.PASSIVE

    fun onMotionStateChanged(newState: WristState) {
        if (newState != lastKnownState) {
            lastKnownState = newState
            _currentProfile.value = getProfileForState(newState)
            // Trigger actual encoder/network adaptations here
        }
    }

    private fun getProfileForState(state: WristState): StreamProfile {
        return when (state) {
            WristState.ACTIVE_VIEWING -> StreamProfile(
                width = 1080, height = 1080, maxFps = 60, targetBitrateKbps = 5000, gopSize = 60
            )
            WristState.PASSIVE -> StreamProfile(
                width = 720, height = 720, maxFps = 30, targetBitrateKbps = 2500, gopSize = 30
            )
            WristState.IDLE -> StreamProfile(
                width = 480, height = 480, maxFps = 15, targetBitrateKbps = 800, gopSize = 60
            )
            WristState.SLEEP -> StreamProfile(
                width = 240, height = 240, maxFps = 5, targetBitrateKbps = 200, gopSize = 120
            )
        }
    }
}
