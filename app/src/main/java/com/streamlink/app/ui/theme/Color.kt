package com.streamlink.app.ui.theme

import androidx.compose.ui.graphics.Color

// ══════════════════════════════════════════════════════
//  HORUS LINK Design System — DESIGN.md palette (Dark-first)
// ══════════════════════════════════════════════════════
object DarkColors {
    val Primary              = Color(0xFFBDC2FF)   // indigo-light
    val OnPrimary            = Color(0xFF131E8C)
    val PrimaryContainer     = Color(0xFF818CF8)   // indigo-medium
    val OnPrimaryContainer   = Color(0xFF101B8A)
    val Secondary            = Color(0xFFFFFFFF)
    val OnSecondary          = Color(0xFF00382B)
    val SecondaryContainer   = Color(0xFF24FFCD)   // cyber-cyan
    val OnSecondaryContainer = Color(0xFF00725A)
    val Tertiary             = Color(0xFF4EDEA3)   // emerald
    val OnTertiary           = Color(0xFF003824)
    val TertiaryContainer    = Color(0xFF00AA76)
    val OnTertiaryContainer  = Color(0xFF003522)
    val Background           = Color(0xFF0F141B)   // deep-space
    val OnBackground         = Color(0xFFDEE2EC)
    val Surface              = Color(0xFF0F141B)
    val OnSurface            = Color(0xFFDEE2EC)
    val SurfaceVariant       = Color(0xFF30353D)
    val OnSurfaceVariant     = Color(0xFFC6C5D5)
    val Outline              = Color(0xFF908F9E)
    val OutlineVariant       = Color(0xFF454653)
    val Error                = Color(0xFFFFB4AB)
    val OnError              = Color(0xFF690005)
    val ErrorContainer       = Color(0xFF93000A)
    val OnErrorContainer     = Color(0xFFFFDAD6)
    val InverseSurface       = Color(0xFFDEE2EC)
    val InverseOnSurface     = Color(0xFF2C3138)
    val InversePrimary       = Color(0xFF4953BC)
    val SurfaceTint          = Color(0xFFBDC2FF)
    // Surface container tiers (for glassmorphism layers)
    val SurfaceContainerLowest  = Color(0xFF0A0E14)
    val SurfaceContainerLow     = Color(0xFF141920)
    val SurfaceContainer        = Color(0xFF1A1F27)
    val SurfaceContainerHigh    = Color(0xFF242932)
    val SurfaceContainerHighest = Color(0xFF30353D)
    // Semantic / state colours from DESIGN.md
    val WarningState         = Color(0xFFFFAA00)
    val ErrorState           = Color(0xFFFF3366)
}

// Light palette — same hue family, lighter surfaces
object LightColors {
    val Primary              = Color(0xFF4953BC)
    val OnPrimary            = Color(0xFFFFFFFF)
    val PrimaryContainer     = Color(0xFFDEE0FF)
    val OnPrimaryContainer   = Color(0xFF080F6C)
    val Secondary            = Color(0xFF006B53)
    val OnSecondary          = Color(0xFFFFFFFF)
    val SecondaryContainer   = Color(0xFF80F9D5)
    val OnSecondaryContainer = Color(0xFF002118)
    val Tertiary             = Color(0xFF006B49)
    val OnTertiary           = Color(0xFFFFFFFF)
    val TertiaryContainer    = Color(0xFF85F7C2)
    val OnTertiaryContainer  = Color(0xFF002114)
    val Background           = Color(0xFFFAF8FF)
    val OnBackground         = Color(0xFF1B1B23)
    val Surface              = Color(0xFFFAF8FF)
    val OnSurface            = Color(0xFF1B1B23)
    val SurfaceVariant       = Color(0xFFE3E1F0)
    val OnSurfaceVariant     = Color(0xFF46454F)
    val Outline              = Color(0xFF777680)
    val OutlineVariant       = Color(0xFFC7C5D5)
    val Error                = Color(0xFFBA1A1A)
    val OnError              = Color(0xFFFFFFFF)
    val ErrorContainer       = Color(0xFFFFDAD6)
    val OnErrorContainer     = Color(0xFF410002)
    val InverseSurface       = Color(0xFF303038)
    val InverseOnSurface     = Color(0xFFF2EFF9)
    val InversePrimary       = Color(0xFFBDC2FF)
    val SurfaceTint          = Color(0xFF4953BC)
    val WarningState         = Color(0xFFF59E0B)
    val ErrorState           = Color(0xFFDC2626)
}

object SemanticColors {
    val Excellent  = Color(0xFF4EDEA3)   // tertiary-emerald
    val Good       = Color(0xFF8BC34A)
    val Degraded   = Color(0xFFFFAA00)   // warning
    val Poor       = Color(0xFFFF3366)   // error-state
    val Neutral    = Color(0xFF454653)
    val Streaming  = Color(0xFF4EDEA3)
    val Connecting = Color(0xFFFFAA00)
    val Idle       = Color(0xFF454653)
    val CyberCyan  = Color(0xFF24FFCD)
}
