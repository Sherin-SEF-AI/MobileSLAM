package com.mappilot.app.sessions

import androidx.compose.foundation.clickable
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
import com.mappilot.app.ui.theme.MapPilotColors
import com.mappilot.app.ui.theme.TelemetryTextStyle
import com.mappilot.core.common.time.NANOS_PER_SECOND
import com.mappilot.core.database.MapPilotRepository
import com.mappilot.core.model.Trip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import javax.inject.Inject

@HiltViewModel
class SessionsViewModel @Inject constructor(repository: MapPilotRepository) : ViewModel() {
    val trips: StateFlow<List<Trip>> =
        repository.observeTrips().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** Real Sessions list backed by the trip database. */
@Composable
fun SessionsScreen(
    onOpenTrip: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val trips by viewModel.trips.collectAsStateWithLifecycle()
    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        Text("Sessions", style = MaterialTheme.typography.titleLarge)
        if (trips.isEmpty()) {
            Text(
                "No sessions recorded yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MapPilotColors.OnSurfaceMuted,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
            items(trips) { trip -> TripRow(trip) { onOpenTrip(trip.id) } }
        }
    }
}

@Composable
private fun TripRow(trip: Trip, onClick: () -> Unit) {
    val durationS = ((trip.endedNs ?: trip.startedNs) - trip.startedNs) / NANOS_PER_SECOND
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("Trip ${trip.id}", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "%.0fs · %.1f m · slam %.0f%% · gnss %.0f%% · %s".format(
                durationS.toDouble(), trip.distanceM, trip.slamScore * 100, trip.gnssScore * 100, trip.status.name,
            ),
            style = TelemetryTextStyle,
            color = MapPilotColors.OnSurfaceMuted,
        )
    }
}
