package com.mappilot.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * "Operational Materialism" palette: matte dark surfaces, neutral by default,
 * colour earned only by state.
 */
object MapPilotColors {
    // Matte dark surfaces
    val Background = Color(0xFF0A0A0A)
    val Surface = Color(0xFF141414)
    val SurfaceVariant = Color(0xFF1F1F1F)
    val Outline = Color(0xFF2E2E2E)

    // Neutral text/telemetry
    val OnSurface = Color(0xFFE6E6E6)
    val OnSurfaceMuted = Color(0xFF9A9A9A)

    // State colours — used ONLY to signal state.
    val Recording = Color(0xFFFF1744) // red: recording active
    val TrackingLock = Color(0xFF00E676) // green: tracking lock / healthy
    val Degraded = Color(0xFFFFB300) // amber: degraded / throttled
    val Idle = Color(0xFF607D8B) // neutral blue-grey: idle
}
