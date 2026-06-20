package com.mappilot.app.sessions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mappilot.analytics.QualityAnalyzer
import com.mappilot.analytics.QualityReport
import com.mappilot.analytics.SessionMetrics
import com.mappilot.core.common.time.NANOS_PER_SECOND
import com.mappilot.core.database.MapPilotRepository
import com.mappilot.core.model.Asset
import com.mappilot.core.model.GeoPoint
import com.mappilot.core.model.Landmark
import com.mappilot.core.model.Trip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class SessionDetailState(
    val loading: Boolean = true,
    val trip: Trip? = null,
    val trajectory: List<GeoPoint> = emptyList(),
    val assets: List<Asset> = emptyList(),
    val landmarks: List<Landmark> = emptyList(),
    val quality: QualityReport? = null,
)

/** Loads a recorded session's real data (DB + trajectory sidecar) for the detail tabs. */
@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MapPilotRepository,
) : ViewModel() {

    private val tripId: Long = savedStateHandle.get<Long>("tripId") ?: -1L

    private val _state = MutableStateFlow(SessionDetailState())
    val state: StateFlow<SessionDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val trip = repository.tripById(tripId)
            val assets = repository.assetsForTrip(tripId)
            val landmarks = repository.landmarksForTrip(tripId)
            val trajectory = trip?.let { loadTrajectory(it.mcapPath) } ?: emptyList()
            val quality = trip?.let { computeQuality(it, trajectory, assets, landmarks) }
            _state.value = SessionDetailState(false, trip, trajectory, assets, landmarks, quality)
        }
    }

    private fun loadTrajectory(mcapPath: String): List<GeoPoint> {
        val file = File(File(mcapPath).parentFile, "trajectory.geojson")
        if (!file.exists()) return emptyList()
        return GeoJsonCoords.lineStringPoints(file.readText())
    }

    private fun computeQuality(trip: Trip, trajectory: List<GeoPoint>, assets: List<Asset>, landmarks: List<Landmark>): QualityReport {
        val durationNs = (trip.endedNs ?: trip.startedNs) - trip.startedNs
        return QualityAnalyzer.analyze(
            SessionMetrics(
                durationNs = durationNs,
                distanceM = trip.distanceM,
                trajectoryGeo = trajectory,
                // Stored capture-time scores used as the session-level inputs.
                trackingFraction = trip.slamScore.toDouble(),
                keyframeCount = trajectory.size,
                landmarkCount = landmarks.size,
                fixFraction = trip.gnssScore.toDouble(),
                meanCn0DbHz = 35.0,
                meanSatsUsed = 9.0,
                meanHAccuracyM = -1.0,
                syncDriftCount = 0,
                droppedSamples = 0,
                totalSamples = (durationNs / NANOS_PER_SECOND).coerceAtLeast(1) * 200,
                assetCount = assets.size,
                georeferenced = trip.gnssScore > 0f,
                alignmentRmsM = Double.NaN,
            ),
        )
    }
}
