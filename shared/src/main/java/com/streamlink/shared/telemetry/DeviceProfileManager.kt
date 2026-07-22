package com.streamlink.shared.telemetry

import android.os.Build

enum class PerformanceTier {
    FLAGSHIP, HIGH, MID, LOW
}

data class DeviceProfile(
    val tier: PerformanceTier,
    val maxEncoderBitrate: Int,
    val supportsHevc: Boolean,
    val defaultJitterMs: Int
)

object DeviceProfileManager {

    fun getDeviceProfile(): DeviceProfile {
        val tier = determineTier()
        
        return DeviceProfile(
            tier = tier,
            maxEncoderBitrate = when (tier) {
                PerformanceTier.FLAGSHIP -> 12_000_000 // 12 Mbps
                PerformanceTier.HIGH -> 8_000_000      // 8 Mbps
                PerformanceTier.MID -> 5_000_000       // 5 Mbps
                PerformanceTier.LOW -> 2_500_000       // 2.5 Mbps
            },
            supportsHevc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && (tier == PerformanceTier.FLAGSHIP || tier == PerformanceTier.HIGH),
            defaultJitterMs = when (tier) {
                PerformanceTier.FLAGSHIP -> 20
                PerformanceTier.HIGH -> 35
                PerformanceTier.MID -> 60
                PerformanceTier.LOW -> 100
            }
        )
    }

    private fun determineTier(): PerformanceTier {
        val ramMb = getRamMb()
        val cores = Runtime.getRuntime().availableProcessors()

        // Basic heuristic. Can be enriched with a cloud-synced database later.
        return when {
            ramMb >= 8000 && cores >= 8 -> PerformanceTier.FLAGSHIP
            ramMb >= 6000 && cores >= 8 -> PerformanceTier.HIGH
            ramMb >= 4000 && cores >= 6 -> PerformanceTier.MID
            else -> PerformanceTier.LOW
        }
    }

    private fun getRamMb(): Long {
        val rt = Runtime.getRuntime()
        return rt.maxMemory() / (1024 * 1024) // Fallback estimation based on JVM max heap limit.
    }
}
