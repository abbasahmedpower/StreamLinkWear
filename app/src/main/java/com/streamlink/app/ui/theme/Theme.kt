package com.streamlink.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

private val HorusLinkDarkScheme = darkColorScheme(
    primary              = DarkColors.Primary,
    onPrimary            = DarkColors.OnPrimary,
    primaryContainer     = DarkColors.PrimaryContainer,
    onPrimaryContainer   = DarkColors.OnPrimaryContainer,
    secondary            = DarkColors.Secondary,
    onSecondary          = DarkColors.OnSecondary,
    secondaryContainer   = DarkColors.SecondaryContainer,
    onSecondaryContainer = DarkColors.OnSecondaryContainer,
    tertiary             = DarkColors.Tertiary,
    onTertiary           = DarkColors.OnTertiary,
    tertiaryContainer    = DarkColors.TertiaryContainer,
    onTertiaryContainer  = DarkColors.OnTertiaryContainer,
    background           = DarkColors.Background,
    onBackground         = DarkColors.OnBackground,
    surface              = DarkColors.Surface,
    onSurface            = DarkColors.OnSurface,
    surfaceVariant       = DarkColors.SurfaceVariant,
    onSurfaceVariant     = DarkColors.OnSurfaceVariant,
    outline              = DarkColors.Outline,
    outlineVariant       = DarkColors.OutlineVariant,
    error                = DarkColors.Error,
    onError              = DarkColors.OnError,
    errorContainer       = DarkColors.ErrorContainer,
    onErrorContainer     = DarkColors.OnErrorContainer,
    inverseSurface       = DarkColors.InverseSurface,
    inverseOnSurface     = DarkColors.InverseOnSurface,
    inversePrimary       = DarkColors.InversePrimary,
    surfaceTint          = DarkColors.SurfaceTint
)

private val HorusLinkLightScheme = lightColorScheme(
    primary              = LightColors.Primary,
    onPrimary            = LightColors.OnPrimary,
    primaryContainer     = LightColors.PrimaryContainer,
    onPrimaryContainer   = LightColors.OnPrimaryContainer,
    secondary            = LightColors.Secondary,
    onSecondary          = LightColors.OnSecondary,
    secondaryContainer   = LightColors.SecondaryContainer,
    onSecondaryContainer = LightColors.OnSecondaryContainer,
    tertiary             = LightColors.Tertiary,
    onTertiary           = LightColors.OnTertiary,
    tertiaryContainer    = LightColors.TertiaryContainer,
    onTertiaryContainer  = LightColors.OnTertiaryContainer,
    background           = LightColors.Background,
    onBackground         = LightColors.OnBackground,
    surface              = LightColors.Surface,
    onSurface            = LightColors.OnSurface,
    surfaceVariant       = LightColors.SurfaceVariant,
    onSurfaceVariant     = LightColors.OnSurfaceVariant,
    outline              = LightColors.Outline,
    outlineVariant       = LightColors.OutlineVariant,
    error                = LightColors.Error,
    onError              = LightColors.OnError,
    errorContainer       = LightColors.ErrorContainer,
    onErrorContainer     = LightColors.OnErrorContainer,
    inverseSurface       = LightColors.InverseSurface,
    inverseOnSurface     = LightColors.InverseOnSurface,
    inversePrimary       = LightColors.InversePrimary,
    surfaceTint          = LightColors.SurfaceTint
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun StreamLinkTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK   -> true
        ThemeMode.LIGHT  -> false
    }
    MaterialTheme(
        colorScheme = if (darkTheme) HorusLinkDarkScheme else HorusLinkLightScheme,
        typography  = streamLinkTypography(),
        content     = content
    )
}

@Composable
fun ForceLtr(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}
