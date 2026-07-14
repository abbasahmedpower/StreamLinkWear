package com.streamlink.shared

/**
 * Defines streaming quality presets.
 *
 * Each mode is a self-contained configuration — a Single Source of Truth
 * for the encoder bitrate, frame rate, and the human-readable labels shown in UI.
 *
 * Design rationale:
 *  - UI reads [label] and [description] for display — no context needed.
 *  - [HardwareEncoder] reads [targetBitrateKbps] directly (no switch/when at call-site).
 *  - [targetFps] is used by the encoder's frame pacing logic.
 *
 * Note: [label] and [description] are plain English strings. Localization
 * is the responsibility of the UI layer (res/values/strings.xml per module).
 */
enum class QualityMode(
    val label: String,
    val description: String,
    val targetBitrateKbps: Int,
    val targetFps: Int
) {

    BATTERY_SAVER(
        label             = "Battery Saver 🔋",
        description       = "Smooth at low power. Best for all-day use.",
        targetBitrateKbps = 1000,
        targetFps         = 24
    ),

    BALANCED(
        label             = "Balanced ⚖️",
        description       = "Sharp and fluid. Recommended for most use cases.",
        targetBitrateKbps = 2500,
        targetFps         = 30
    ),

    HIGH_QUALITY(
        label             = "High Quality ✨",
        description       = "Maximum fidelity. Requires strong Wi-Fi and battery.",
        targetBitrateKbps = 6000,
        targetFps         = 60
    );

    companion object {
        /**
         * Safe deserializer — falls back to [BALANCED] on any unknown value
         * (e.g., from an older app version that saved a different enum name).
         */
        fun fromName(name: String?): QualityMode =
            entries.firstOrNull { it.name == name } ?: BALANCED
    }
}

data class StreamConfig(
    val mode: QualityMode,
    val resolution: ResolutionProfile
)
