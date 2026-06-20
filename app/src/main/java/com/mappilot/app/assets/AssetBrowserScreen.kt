package com.mappilot.app.assets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mappilot.app.ui.theme.MapPilotColors
import com.mappilot.app.ui.theme.TelemetryTextStyle
import com.mappilot.core.model.Asset
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
            ClassChip("ALL", state.selectedClass == null && state.similarToId == null) { viewModel.filterByClass(null) }
            FILTERS.forEach { c ->
                ClassChip(c.name.removePrefix("TRAFFIC_"), state.selectedClass == c) { viewModel.filterByClass(c) }
            }
        }

        // Visual-similarity affordance / status.
        when {
            state.similarToId != null -> Text(
                "Showing assets visually similar to #${state.similarToId} · tap ALL to clear",
                style = TelemetryTextStyle,
                fontWeight = FontWeight.Bold,
                color = MapPilotColors.TrackingLock,
                modifier = Modifier.padding(top = 6.dp),
            )
            state.embeddingsAvailable -> Text(
                "Tap an asset to find visually similar ones (on-device embeddings).",
                style = TelemetryTextStyle,
                color = MapPilotColors.OnSurfaceMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
            else -> Text(
                "Visual similarity UNAVAILABLE — no embeddings yet (capture with the image embedder).",
                style = TelemetryTextStyle,
                color = MapPilotColors.OnSurfaceMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
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
            itemsIndexed(state.results) { index, asset ->
                // Page in more as the user nears the end (100 GB-scale lists).
                if (index >= state.results.size - 10 && state.hasMore) viewModel.loadMore()
                val rowModifier = if (state.embeddingsAvailable) {
                    Modifier.fillMaxWidth().clickable { viewModel.findSimilar(asset) }.padding(vertical = 6.dp)
                } else {
                    Modifier.fillMaxWidth().padding(vertical = 6.dp)
                }
                Row(
                    modifier = rowModifier,
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
