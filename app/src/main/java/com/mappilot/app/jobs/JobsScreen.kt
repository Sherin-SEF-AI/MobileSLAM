package com.mappilot.app.jobs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mappilot.app.ui.theme.MapPilotColors
import com.mappilot.app.ui.theme.TelemetryTextStyle
import com.mappilot.core.database.MapPilotRepository
import com.mappilot.core.model.Provenance
import com.mappilot.core.model.UploadJob
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class JobsViewModel @Inject constructor(repository: MapPilotRepository) : ViewModel() {
    val jobs: StateFlow<List<UploadJob>> =
        repository.observeUploadJobs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** Upload + cloud job states with provenance. Cloud-derived artifacts are tagged. */
@Composable
fun JobsScreen(modifier: Modifier = Modifier, viewModel: JobsViewModel = hiltViewModel()) {
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        Text("Jobs / Upload", style = MaterialTheme.typography.titleLarge)
        if (jobs.isEmpty()) {
            Text(
                "No upload or processing jobs.",
                style = MaterialTheme.typography.bodyMedium,
                color = MapPilotColors.OnSurfaceMuted,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
            items(jobs) { job -> JobRow(job) }
        }
    }
}

@Composable
private fun JobRow(job: UploadJob) {
    val stateColor = when (job.state) {
        "READY" -> MapPilotColors.TrackingLock
        "FAILED" -> MapPilotColors.Recording
        "UPLOADING", "PROCESSING" -> MapPilotColors.Degraded
        else -> MapPilotColors.Idle
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
            Text("${job.artifact} · trip ${job.tripId}", style = MaterialTheme.typography.titleMedium)
            Text(job.state, style = TelemetryTextStyle, color = stateColor)
        }
        // Provenance is always shown so cloud artifacts are distinguished from on-device.
        Text(
            "provenance: ${job.provenance.name}" +
                if (job.provenance != Provenance.ON_DEVICE) "  ⟂ cloud-derived" else "",
            style = TelemetryTextStyle,
            color = MapPilotColors.OnSurfaceMuted,
        )
    }
}
