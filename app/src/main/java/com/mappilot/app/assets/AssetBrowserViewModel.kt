package com.mappilot.app.assets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mappilot.core.database.MapPilotRepository
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
    val hasMore: Boolean = false,
)

/** Backs the Asset Browser: real spatial/attribute queries + paged scans over the asset DB. */
@HiltViewModel
class AssetBrowserViewModel @Inject constructor(
    private val search: SearchService,
    private val repository: MapPilotRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AssetBrowserState())
    val state: StateFlow<AssetBrowserState> = _state.asStateFlow()
    private var pageOffset = 0

    /** Filter by class (null = paged scan over all assets, 100 GB-safe). */
    fun filterByClass(assetClass: AssetClass?) {
        pageOffset = 0
        _state.value = AssetBrowserState(loading = true, selectedClass = assetClass)
        viewModelScope.launch {
            val total = repository.assetCountTotal()
            if (assetClass != null) {
                val results = search.assetsByClass(assetClass)
                _state.value = AssetBrowserState(false, assetClass, results, results.size, hasMore = false)
            } else {
                val page = repository.assetsPage(0, PAGE_SIZE)
                pageOffset = page.size
                _state.value = AssetBrowserState(false, null, page, total, hasMore = pageOffset < total)
            }
        }
    }

    /** Load the next page (only for the unfiltered scan). */
    fun loadMore() {
        if (_state.value.selectedClass != null || !_state.value.hasMore) return
        viewModelScope.launch {
            val page = repository.assetsPage(pageOffset, PAGE_SIZE)
            pageOffset += page.size
            val merged = _state.value.results + page
            _state.value = _state.value.copy(results = merged, hasMore = pageOffset < _state.value.totalAssets)
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
        const val PAGE_SIZE = 200
    }
}
