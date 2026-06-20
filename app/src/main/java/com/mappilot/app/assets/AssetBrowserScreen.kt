package com.mappilot.app.assets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mappilot.app.ui.theme.MapPilotColors
import com.mappilot.app.ui.theme.TelemetryTextStyle
import com.mappilot.core.model.AssetClass

/**
 * Phase-5 Asset Browser: real spatial + attribute search over the on-device asset
 * database. No placeholder rows — results come from [com.mappilot.search.SearchService].
 */
@Composable
fun AssetBrowserScreen(
    modifier: Modifier = Modifier,
    viewModel: AssetBrowserViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.filterByClass(null) }

    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        Text("Asset Browser", style = MaterialTheme.typography.titleLarge)
        Text(
            "${state.totalAssets} assets" + if (state.loading) " · loading…" else "",
            style = TelemetryTextStyle,
            color = MapPilotColors.OnSurfaceMuted,
            modifier = Modifier.padding(vertical = 6.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ClassChip("ALL", state.selectedClass == null) { viewModel.filterByClass(null) }
            FILTERS.forEach { c ->
                ClassChip(c.name.removePrefix("TRAFFIC_"), state.selectedClass == c) { viewModel.filterByClass(c) }
            }
        }

        if (state.results.isEmpty() && !state.loading) {
            Text(
                "No assets recorded yet. Capture a session with perception + georeferencing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MapPilotColors.OnSurfaceMuted,
                modifier = Modifier.padding(top = 24.dp),
            )
        }

        LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
            items(state.results) { asset ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(asset.assetClass.name, style = TelemetryTextStyle, color = MapPilotColors.TrackingLock)
                    Text(
                        "%.5f, %.5f  ±%.0fm  %.2f".format(
                            asset.geo.latitude, asset.geo.longitude, asset.depthM ?: Float.NaN, asset.confidence,
                        ),
                        style = TelemetryTextStyle,
                        color = MapPilotColors.OnSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClassChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, style = TelemetryTextStyle) })
}

private val FILTERS = listOf(
    AssetClass.TRAFFIC_LIGHT, AssetClass.TRAFFIC_SIGN, AssetClass.POLE,
    AssetClass.POTHOLE, AssetClass.CROSSWALK,
)
