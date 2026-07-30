package com.paperpanorama.ocr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Lime primary on Canvas neutrals (design-system/MASTER.md).
 * Camera chrome: near-black with lime accents.
 */
private val LightColors = lightColorScheme(
    primary = Lime400,
    onPrimary = Ink,
    primaryContainer = Lime50,
    onPrimaryContainer = Ink,
    secondary = Lime300,
    onSecondary = Ink,
    secondaryContainer = SurfaceMuted,
    onSecondaryContainer = Ink,
    tertiary = Lime200,
    onTertiary = Ink,
    background = Canvas,
    onBackground = Ink,
    surface = Canvas,
    onSurface = Ink,
    surfaceVariant = SurfaceMuted,
    onSurfaceVariant = Muted,
    outline = Border,
    outlineVariant = Border,
    error = ErrorSoft,
    onError = Ink,
)

/** Immersive camera / stitch — lime chrome on near-black. */
val CameraColorScheme = darkColorScheme(
    primary = Lime400,
    onPrimary = Ink,
    secondary = Lime200,
    onSecondary = Ink,
    tertiary = Lime50,
    onTertiary = Ink,
    background = CameraBlack,
    onBackground = OnCamera,
    surface = CameraChrome,
    onSurface = OnCamera,
    surfaceVariant = CameraChrome,
    onSurfaceVariant = OnCamera.copy(alpha = 0.75f),
    outline = Border.copy(alpha = 0.4f),
    error = ErrorSoft,
    onError = Ink,
)

private val DarkColors = darkColorScheme(
    primary = Lime400,
    onPrimary = Ink,
    secondary = Lime200,
    onSecondary = Ink,
    tertiary = Lime50,
    onTertiary = Ink,
    background = CameraChrome,
    onBackground = OnCamera,
    surface = CameraChrome,
    onSurface = OnCamera,
    error = ErrorSoft,
    onError = Ink,
)

@Composable
fun PaperPanoramaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    cameraChrome: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        cameraChrome -> CameraColorScheme
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = PaperPanoramaTypography,
        content = content,
    )
}
