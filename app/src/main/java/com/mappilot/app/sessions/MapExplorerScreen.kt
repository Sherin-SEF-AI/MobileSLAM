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
import com.mappilot.viz.map.MapLibreMapView
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MapExplorerViewModel @Inject constructor(private val repository: MapPilotRepository) : ViewModel() {
    private val _assets = MutableStateFlow<List<Asset>>(emptyList())
    val assets: StateFlow<List<Asset>> = _assets.asStateFlow()

    init {
        viewModelScope.launch { _assets.value = repository.allAssets() }
    }
}

/** Multi-session asset map across the whole database. */
@Composable
fun MapExplorerScreen(modifier: Modifier = Modifier, viewModel: MapExplorerViewModel = hiltViewModel()) {
    val assets by viewModel.assets.collectAsStateWithLifecycle()
    Box(modifier = modifier.fillMaxSize()) {
        MapLibreMapView(trajectory = emptyList(), assets = assets, modifier = Modifier.fillMaxSize())
        Text(
            "${assets.size} assets",
            style = TelemetryTextStyle,
            modifier = Modifier.padding(12.dp),
        )
    }
}
