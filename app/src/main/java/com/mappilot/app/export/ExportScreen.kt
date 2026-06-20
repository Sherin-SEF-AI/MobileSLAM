package com.mappilot.app.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mappilot.app.ui.theme.MapPilotColors
import com.mappilot.app.ui.theme.TelemetryTextStyle
import com.mappilot.export.ExportFormat

/**
 * Phase-7 Export: device-native formats run inline; cloud-only formats are
 * clearly marked "requires processing" and dispatched as jobs — never fabricated.
 */
@Composable
fun ExportScreen(
    tripId: Long,
    modifier: Modifier = Modifier,
    viewModel: ExportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Export · Trip $tripId", style = MaterialTheme.typography.titleLarge)

        Section("Device-native (run on phone)")
        FormatChips(ExportFormat.deviceFormats, state, viewModel)

        Section("Cloud-only (requires processing)")
        FormatChips(ExportFormat.cloudFormats, state, viewModel)

        Button(
            onClick = viewModel::runExport,
            enabled = !state.running && state.selected.isNotEmpty(),
            modifier = Modifier.padding(top = 16.dp),
        ) { Text(if (state.running) "Exporting…" else "Export") }

        if (state.deviceFiles.isNotEmpty()) {
            Section("Files written")
            state.deviceFiles.forEach {
                Text("✓ ${it.substringAfterLast('/')}", style = TelemetryTextStyle, color = MapPilotColors.TrackingLock)
            }
        }
        if (state.cloudJobs.isNotEmpty()) {
            Section("Cloud jobs dispatched")
            state.cloudJobs.forEach {
                Text("⤴ ${it.format.name}: ${it.state} — ${it.note}", style = TelemetryTextStyle, color = MapPilotColors.Degraded)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatChips(formats: List<ExportFormat>, state: ExportUiState, viewModel: ExportViewModel) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        formats.forEach { f ->
            FilterChip(
                selected = f in state.selected,
                onClick = { viewModel.toggle(f) },
                label = { Text(f.name, style = TelemetryTextStyle) },
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MapPilotColors.OnSurfaceMuted,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}
