package com.mappilot.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector

/** Every navigable destination in the app. */
enum class MapPilotDestination(val route: String) {
    Capture("capture"),
    Sessions("sessions"),
    SessionDetail("sessions/{tripId}"),
    MapExplorer("map_explorer"),
    AssetBrowser("asset_browser"),
    Export("export/{tripId}"),
    Jobs("jobs"),
    Settings("settings");

    companion object {
        fun sessionDetailRoute(tripId: Long) = "sessions/$tripId"
        fun exportRoute(tripId: Long) = "export/$tripId"
    }
}

/** Top-level destinations shown in the bottom navigation bar. */
enum class TopLevelDestination(
    val destination: MapPilotDestination,
    val label: String,
    val icon: ImageVector,
) {
    CAPTURE(MapPilotDestination.Capture, "Capture", Icons.Filled.RadioButtonChecked),
    SESSIONS(MapPilotDestination.Sessions, "Sessions", Icons.Filled.VideoLibrary),
    MAP(MapPilotDestination.MapExplorer, "Map", Icons.Filled.Map),
    ASSETS(MapPilotDestination.AssetBrowser, "Assets", Icons.Filled.Search),
    SETTINGS(MapPilotDestination.Settings, "Settings", Icons.Filled.Settings),
}
