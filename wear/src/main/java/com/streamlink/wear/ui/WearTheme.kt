package com.streamlink.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Typography
import androidx.wear.compose.material.Colors

/**
 * StreamLinkWear design system for Wear OS.
 *
 * Design principle: minimum chrome, maximum readability on a 466×466 AMOLED.
 * Pure black background to preserve OLED pixels and battery.
 * Accent: #00FF88 (streaming active) / #00AAFF (connecting) / #FF4444 (error).
 */
object WearTheme {

    val StreamGreen = Color(0xFF00FF88)
    val ConnectBlue = Color(0xFF00AAFF)
    val WarnOrange  = Color(0xFFFFAA00)
    val ErrorRed    = Color(0xFFFF4444)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextMuted   = Color(0xFF888888)
    val Background  = Color(0xFF000000)

    private val wearColors = Colors(
        primary            = StreamGreen,
        primaryVariant     = Color(0xFF00CC66),
        secondary          = ConnectBlue,
        secondaryVariant   = Color(0xFF0077CC),
        error              = ErrorRed,
        onPrimary          = Color.Black,
        onSecondary        = Color.Black,
        onError            = Color.White,
        background         = Background,
        onBackground       = TextPrimary,
        surface            = Color(0xFF111111),
        onSurface          = TextPrimary
    )

    private val wearTypography = Typography(
        // Wear OS uses compact type — nothing above 16sp on a 466px round screen
    )

    @Composable
    operator fun invoke(content: @Composable () -> Unit) {
        MaterialTheme(
            colors = wearColors,
            typography = wearTypography,
            content = content
        )
    }
}
