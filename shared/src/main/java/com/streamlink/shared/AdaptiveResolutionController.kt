package com.streamlink.shared

data class ResolutionProfile(
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int
)

class AdaptiveResolutionController {
    private val profiles = listOf(
        ResolutionProfile("ECO",  StreamProtocol.WEAR_W_ECO,  StreamProtocol.WEAR_H_ECO,  StreamProtocol.WEAR_FPS_ECO,  StreamProtocol.WEAR_BPS_ECO),
        ResolutionProfile("FULL", StreamProtocol.WEAR_W_FULL, StreamProtocol.WEAR_H_FULL, StreamProtocol.WEAR_FPS_FULL, StreamProtocol.WEAR_BPS_FULL)
    )

    @Volatile private var currentIndex = 1  // Start at FULL

    fun currentResolution(): ResolutionProfile = profiles[currentIndex]

    fun determine(rttMs: Long, cpuLoad: Float, thermalLevel: Int): ResolutionProfile {
        currentIndex = when {
            thermalLevel > 7 || rttMs > 200 || cpuLoad > 0.85f -> 0  // ECO
            thermalLevel < 5 && rttMs < 80 && cpuLoad < 0.6f -> 1    // FULL
            else -> currentIndex  // Hold current
        }
        return profiles[currentIndex]
    }
}
