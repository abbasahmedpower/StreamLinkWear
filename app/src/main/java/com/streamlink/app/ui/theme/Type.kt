package com.streamlink.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Using system sans-serif families to avoid GoogleFont certs dependency.
// Swap to GoogleFont when com_google_android_gms_fonts_certs array is bundled.
private val DefaultFamily  = FontFamily.SansSerif

@Composable
fun appFontFamily(): FontFamily {
    // Cairo is RTL-optimised; falls back to default for all other locales.
    val lang = LocalConfiguration.current.locales[0].language
    // Both map to system SansSerif at runtime — swap when bundling custom fonts.
    return DefaultFamily
}

@Composable
fun streamLinkTypography(): Typography {
    val font = appFontFamily()
    return Typography(
        displayLarge  = TextStyle(fontFamily = font, fontWeight = FontWeight.Bold,     fontSize = 48.sp, lineHeight = 56.sp, letterSpacing = (-0.02).sp),
        headlineLarge = TextStyle(fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp),
        titleMedium   = TextStyle(fontFamily = font, fontWeight = FontWeight.Medium,   fontSize = 18.sp, lineHeight = 24.sp),
        bodyLarge     = TextStyle(fontFamily = font, fontWeight = FontWeight.Normal,   fontSize = 16.sp, lineHeight = 24.sp),
        bodySmall     = TextStyle(fontFamily = font, fontWeight = FontWeight.Normal,   fontSize = 14.sp, lineHeight = 20.sp),
        labelSmall    = TextStyle(fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.05.sp)
    )
}
