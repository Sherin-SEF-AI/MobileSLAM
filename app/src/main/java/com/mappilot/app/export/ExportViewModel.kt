package com.mappilot.app.export

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mappilot.export.CloudJobDescriptor
import com.mappilot.export.ExportFormat
import com.mappilot.export.ExportService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ExportUiState(
    val tripId: Long,
    val selected: Set<ExportFormat> = setOf(ExportFormat.GEOJSON, ExportFormat.PLY, ExportFormat.CSV),
    val running: Boolean = false,
    val deviceFiles: List<String> = emptyList(),
    val cloudJobs: List<CloudJobDescriptor> = emptyList(),
)

@HiltViewModel
class ExportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val exportService: ExportService,
) : ViewModel() {

    private val tripId: Long = savedStateHandle.get<Long>("tripId") ?: -1L
    private val _state = MutableStateFlow(ExportUiState(tripId))
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    fun toggle(format: ExportFormat) {
        val sel = _state.value.selected.toMutableSet()
        if (!sel.add(format)) sel.remove(format)
        _state.value = _state.value.copy(selected = sel)
    }

    fun runExport() {
        _state.value = _state.value.copy(running = true)
        viewModelScope.launch {
            val dir = File(context.getExternalFilesDir("exports"), "trip_$tripId")
            val result = exportService.export(tripId, dir, _state.value.selected)
            _state.value = _state.value.copy(
                running = false,
                deviceFiles = result.deviceFiles,
                cloudJobs = result.cloudJobs,
            )
        }
    }
}
