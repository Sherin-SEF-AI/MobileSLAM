package com.mappilot.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val MapPilotDarkScheme = darkColorScheme(
    primary = MapPilotColors.TrackingLock,
    onPrimary = MapPilotColors.Background,
    secondary = MapPilotColors.Idle,
    error = MapPilotColors.Recording,
    background = MapPilotColors.Background,
    onBackground = MapPilotColors.OnSurface,
    surface = MapPilotColors.Surface,
    onSurface = MapPilotColors.OnSurface,
    surfaceVariant = MapPilotColors.SurfaceVariant,
    onSurfaceVariant = MapPilotColors.OnSurfaceMuted,
    outline = MapPilotColors.Outline,
)

/**
 * MapPilot is dark-only by design. There is no light theme — the instrument is
 * meant for field use and matte surfaces.
 */
@Composable
fun MapPilotTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = MapPilotDarkScheme,
        typography = MapPilotTypography,
        content = content,
    )
}
