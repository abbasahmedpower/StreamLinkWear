package com.streamlink.app.ui

import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color

object SharedUiConstants {
    val NASA_SPRING_SPEC = spring<Float>(
        dampingRatio = 0.6f,
        stiffness = 400f,
        visibilityThreshold = 0.001f
    )

    val ACCENT_GLOW = Color(0xFF00FFCC)
    val BACKGROUND_DARK = Color(0xFF121212)
    val MISPREDICTION_RED = Color(0xFFFF3B30)
}
