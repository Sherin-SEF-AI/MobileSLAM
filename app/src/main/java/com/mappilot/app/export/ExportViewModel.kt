package com.mappilot.app.export

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mappilot.cloud.client.CloudConfig
import com.mappilot.cloud.client.CloudUploadManager
import com.mappilot.cloud.client.JobType
import com.mappilot.core.database.MapPilotRepository
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
    private val uploadManager: CloudUploadManager,
    private val repository: MapPilotRepository,
    private val cloudConfig: CloudConfig,
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
            // For each dispatched cloud format, upload the MCAP (real artifact) and
            // create the corresponding processing job. No geometry is fabricated.
            if (result.cloudJobs.isNotEmpty()) {
                repository.tripById(tripId)?.let { trip ->
                    val mcap = File(trip.mcapPath)
                    result.cloudJobs.forEach { job ->
                        uploadManager.enqueue(tripId, mcap, jobTypeFor(job), cloudConfig.baseUrl)
                    }
                }
            }
            _state.value = _state.value.copy(
                running = false,
                deviceFiles = result.deviceFiles,
                cloudJobs = result.cloudJobs,
            )
        }
    }

    private fun jobTypeFor(job: CloudJobDescriptor): JobType = when (job.format) {
        ExportFormat.OBJ, ExportFormat.GLTF -> JobType.MVS_DENSE
        ExportFormat.OPENDRIVE -> JobType.MAP_GEN_OPENDRIVE
        ExportFormat.LANELET2 -> JobType.MAP_GEN_LANELET2
        ExportFormat.MBTILES -> JobType.VECTOR_TILES
        else -> JobType.SFM_REFINE
    }
}
