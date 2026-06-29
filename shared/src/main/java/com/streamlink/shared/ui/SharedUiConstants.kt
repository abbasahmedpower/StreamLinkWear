package com.streamlink.shared.ui

import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color

object SharedUiConstants {
    // NASA-grade Spring Spec: Zero over-bounce, hyper-fast response
    val NASA_SPRING_SPEC = spring<Float>(
        dampingRatio = 0.6f,
        stiffness = 400f
    )

    // Unified Glow and Ripple colors for seamless identity
    val ACCENT_GLOW = Color(0xFF00FFCC)
    val BACKGROUND_DARK = Color(0xFF1C1C1E)
    val MISPREDICTION_RED = Color(0xFFFF3366)
}
