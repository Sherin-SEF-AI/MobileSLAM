package com.mappilot.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The eight top-level screens (§9). In Phase 0 they are wired stubs that name
 * the subsystem and the phase that fills them in. Real content arrives per the
 * build plan — never faked ahead of the data that backs it.
 */

@Composable
fun CaptureScreen(modifier: Modifier = Modifier) = PhaseStubScreen(
    title = "Capture",
    subtitle = "Live preview + data-dense HUD (fps, IMU Hz, GNSS, sync offset, SLAM state, thermal) and the record control.",
    landsInPhase = "Phase 1–3",
    modifier = modifier,
)

@Composable
fun SessionsScreen(modifier: Modifier = Modifier) = PhaseStubScreen(
    title = "Sessions",
    subtitle = "Recorded trips with distance, duration, quality chips and upload/processing state.",
    landsInPhase = "Phase 2",
    modifier = modifier,
)

@Composable
fun SessionDetailScreen(tripId: Long, modifier: Modifier = Modifier) = PhaseStubScreen(
    title = "Session $tripId",
    subtitle = "Tabs: Map, 3D point cloud, Assets, Quality, Export.",
    landsInPhase = "Phase 6",
    modifier = modifier,
)

@Composable
fun MapExplorerScreen(modifier: Modifier = Modifier) = PhaseStubScreen(
    title = "Map Explorer",
    subtitle = "Multi-session MapLibre map with asset and heatmap layer toggles.",
    landsInPhase = "Phase 6",
    modifier = modifier,
)

@Composable
fun AssetBrowserScreen(modifier: Modifier = Modifier) = PhaseStubScreen(
    title = "Asset Browser",
    subtitle = "Spatial + semantic search over the road-asset database.",
    landsInPhase = "Phase 5",
    modifier = modifier,
)

@Composable
fun ExportScreen(tripId: Long, modifier: Modifier = Modifier) = PhaseStubScreen(
    title = "Export $tripId",
    subtitle = "Device-native MCAP/GeoJSON/PLY/PCD/CSV inline; cloud-only formats dispatched as jobs.",
    landsInPhase = "Phase 7",
    modifier = modifier,
)

@Composable
fun JobsScreen(modifier: Modifier = Modifier) = PhaseStubScreen(
    title = "Jobs / Upload",
    subtitle = "Resumable upload and cloud job states with provenance.",
    landsInPhase = "Phase 8",
    modifier = modifier,
)

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) = PhaseStubScreen(
    title = "Settings / Calibration",
    subtitle = "Sensor rates, camera calibration entry, model/delegate selection, storage management.",
    landsInPhase = "Phase 1+",
    modifier = modifier,
)
