package com.mappilot.app.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mappilot.app.ui.theme.MapPilotColors
import com.mappilot.app.ui.theme.TelemetryTextStyle
import com.mappilot.viz.map.MapLibreMapView
import com.mappilot.viz.render3d.PointCloudView

/**
 * Session Detail: tabbed real-data view (Map / 3D / Assets / Quality / Export).
 * Map renders the recorded trajectory + assets; 3D the sparse cloud; Quality the
 * analytics dashboard computed from the session — no placeholder numbers.
 */
@Composable
fun SessionDetailScreen(
    tripId: Long,
    onExport: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Map", "3D", "Assets", "Quality", "Export")

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            "Session $tripId" + if (state.loading) " · loading…" else "",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(12.dp),
        )
        ScrollableTabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        when (tab) {
            0 -> MapLibreMapView(state.trajectory, state.assets, modifier = Modifier.fillMaxSize())
            1 -> PointCloudView(state.landmarks, keyframes = emptyList(), modifier = Modifier.fillMaxSize())
            2 -> AssetsTab(state)
            3 -> QualityTab(state)
            4 -> ExportTab(tripId, onExport)
        }
    }
}

@Composable
private fun AssetsTab(state: SessionDetailState) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        items(state.assets) { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), Arrangement.SpaceBetween) {
                Text(a.assetClass.name, style = TelemetryTextStyle, color = MapPilotColors.TrackingLock)
                Text("%.5f, %.5f  %.2f".format(a.geo.latitude, a.geo.longitude, a.confidence), style = TelemetryTextStyle)
            }
        }
    }
}

@Composable
private fun QualityTab(state: SessionDetailState) {
    val q = state.quality
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (q == null) {
            Text("No quality data.", color = MapPilotColors.OnSurfaceMuted)
            return
        }
        QRow("Overall", "%.0f%%".format(q.overall * 100))
        QRow("SLAM quality", "%.0f%%".format(q.slamScore * 100))
        QRow("GNSS quality", "%.0f%%".format(q.gnssScore * 100))
        QRow("Trajectory quality", "%.0f%%".format(q.trajectoryScore * 100))
        QRow("Reconstruction-ready", "%.0f%%".format(q.reconstructionReadiness * 100))
        QRow("Distance", "%.1f m".format(q.distanceM))
        QRow("Coverage area", "%.0f m²".format(q.coverageAreaM2))
        QRow("Assets", q.assetCount.toString())
        QRow("Landmarks", q.landmarkCount.toString())
        QRow("Keyframes", q.keyframeCount.toString())
    }
}

@Composable
private fun QRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), Arrangement.SpaceBetween) {
        Text(label, style = TelemetryTextStyle, color = MapPilotColors.OnSurfaceMuted)
        Text(value, style = TelemetryTextStyle, color = MapPilotColors.OnSurface)
    }
}

@Composable
private fun ExportTab(tripId: Long, onExport: (Long) -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Export MCAP / GeoJSON / PLY / PCD / CSV, or dispatch cloud-format jobs.", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { onExport(tripId) }, modifier = Modifier.padding(top = 16.dp)) { Text("Open Export") }
    }
}
