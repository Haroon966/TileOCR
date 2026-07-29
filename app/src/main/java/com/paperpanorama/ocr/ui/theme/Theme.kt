package com.paperpanorama.ocr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Soft Yellow surfaces + Deep Rich Red actions/text (from brand swatch).
 * Camera chrome: black / deep red-tint with Soft Yellow overlays.
 */
private val LightColors = lightColorScheme(
    primary = DeepRichRed,
    onPrimary = SoftYellow,
    secondary = DeepRichRed,
    onSecondary = SoftYellow,
    tertiary = DeepRichRed,
    onTertiary = SoftYellow,
    background = SoftYellow,
    onBackground = DeepRichRed,
    surface = SoftYellow,
    onSurface = DeepRichRed,
    surfaceVariant = SoftYellow,
    onSurfaceVariant = DeepRichRed.copy(alpha = 0.75f),
    outline = DeepRichRed.copy(alpha = 0.35f),
    error = DeepRichRed,
    onError = SoftYellow,
)

/** Immersive camera / stitch — Soft Yellow chrome on near-black. */
val CameraColorScheme = darkColorScheme(
    primary = SoftYellow,
    onPrimary = DeepRichRed,
    secondary = SoftYellow,
    onSecondary = DeepRichRed,
    tertiary = DeepRichRed,
    onTertiary = SoftYellow,
    background = CameraBlack,
    onBackground = SoftYellow,
    surface = CameraChrome,
    onSurface = SoftYellow,
    error = SoftYellow,
    onError = DeepRichRed,
)

private val ColorDeepBg = androidx.compose.ui.graphics.Color(0xFF2A0705)

private val DarkColors = darkColorScheme(
    primary = SoftYellow,
    onPrimary = DeepRichRed,
    secondary = SoftYellow,
    onSecondary = DeepRichRed,
    tertiary = SoftYellow,
    onTertiary = DeepRichRed,
    background = ColorDeepBg,
    onBackground = SoftYellow,
    surface = CameraChrome,
    onSurface = SoftYellow,
    error = SoftYellow,
    onError = DeepRichRed,
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
