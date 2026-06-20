package com.mappilot.app.assets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mappilot.core.model.Asset
import com.mappilot.core.model.AssetClass
import com.mappilot.search.SearchService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AssetBrowserState(
    val loading: Boolean = false,
    val selectedClass: AssetClass? = null,
    val results: List<Asset> = emptyList(),
    val totalAssets: Int = 0,
)

/** Backs the Asset Browser: real spatial/attribute queries over the asset DB. */
@HiltViewModel
class AssetBrowserViewModel @Inject constructor(
    private val search: SearchService,
) : ViewModel() {

    private val _state = MutableStateFlow(AssetBrowserState())
    val state: StateFlow<AssetBrowserState> = _state.asStateFlow()

    /** Filter by class (null = all road-asset classes shown via union of queries). */
    fun filterByClass(assetClass: AssetClass?) {
        _state.value = _state.value.copy(loading = true, selectedClass = assetClass)
        viewModelScope.launch {
            val results = if (assetClass != null) {
                search.assetsByClass(assetClass)
            } else {
                ROAD_CLASSES.flatMap { search.assetsByClass(it) }
            }
            _state.value = _state.value.copy(loading = false, results = results, totalAssets = results.size)
        }
    }

    /** Assets within [radiusM] of a point, nearest first. */
    fun searchNearby(lat: Double, lon: Double, radiusM: Double, assetClass: AssetClass? = null) {
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            val hits = search.assetsWithinRadius(lat, lon, radiusM, assetClass)
            _state.value = _state.value.copy(loading = false, results = hits.map { it.asset }, totalAssets = hits.size)
        }
    }

    private companion object {
        val ROAD_CLASSES = listOf(
            AssetClass.TRAFFIC_LIGHT, AssetClass.TRAFFIC_SIGN, AssetClass.POLE,
            AssetClass.POTHOLE, AssetClass.SPEED_BREAKER, AssetClass.CROSSWALK,
        )
    }
}
