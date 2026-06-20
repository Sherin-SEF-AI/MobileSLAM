package com.mappilot.app.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mappilot.app.ui.theme.TelemetryTextStyle
import com.mappilot.core.database.MapPilotRepository
import com.mappilot.core.model.Asset
import com.mappilot.core.model.GeoPoint
import com.mappilot.viz.map.MapLibreMapView
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MapExplorerViewModel @Inject constructor(private val repository: MapPilotRepository) : ViewModel() {
    private val _assets = MutableStateFlow<List<Asset>>(emptyList())
    val assets: StateFlow<List<Asset>> = _assets.asStateFlow()

    private val _trajectories = MutableStateFlow<List<List<GeoPoint>>>(emptyList())
    val trajectories: StateFlow<List<List<GeoPoint>>> = _trajectories.asStateFlow()

    init {
        viewModelScope.launch {
            _assets.value = repository.allAssets()
            // Composite every recorded trip's path from its trajectory.geojson sidecar.
            val trips = repository.trips(limit = MAX_TRIPS, offset = 0)
            _trajectories.value = withContext(Dispatchers.IO) {
                trips.mapNotNull { trip ->
                    val f = File(File(trip.mcapPath).parentFile, "trajectory.geojson")
                    if (!f.exists()) return@mapNotNull null
                    runCatching { GeoJsonCoords.lineStringPoints(f.readText()) }
                        .getOrNull()
                        ?.takeIf { it.size >= 2 }
                }
            }
        }
    }

    private companion object {
        const val MAX_TRIPS = 500
    }
}

/** Multi-session map: every trip's path + asset density across the whole database. */
@Composable
fun MapExplorerScreen(modifier: Modifier = Modifier, viewModel: MapExplorerViewModel = hiltViewModel()) {
    val assets by viewModel.assets.collectAsStateWithLifecycle()
    val trajectories by viewModel.trajectories.collectAsStateWithLifecycle()
    Box(modifier = modifier.fillMaxSize()) {
        MapLibreMapView(trajectories = trajectories, assets = assets, modifier = Modifier.fillMaxSize())
        Text(
            "${trajectories.size} trips · ${assets.size} assets",
            style = TelemetryTextStyle,
            modifier = Modifier.padding(12.dp),
        )
    }
}
