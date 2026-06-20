package com.mappilot.app.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mappilot.app.capture.SensorHub
import com.mappilot.app.hardening.DegradationController
import com.mappilot.app.hardening.StorageManager
import com.mappilot.app.hardening.StorageStatus
import com.mappilot.app.ui.theme.MapPilotColors
import com.mappilot.app.ui.theme.TelemetryTextStyle
import com.mappilot.core.common.config.ConfigProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val configProvider: ConfigProvider,
    private val sensorHub: SensorHub,
    private val storageManager: StorageManager,
    val degradation: DegradationController,
) : ViewModel() {
    val config get() = configProvider.current()
    fun storage(): StorageStatus = storageManager.status()
    fun directChannelSupported(): Boolean = runCatching { sensorHub.imu.supportsDirectChannel() }.getOrDefault(false)
    fun cameraTimestampSource(): String = sensorHub.camera.timestampSource.name
}

/** Settings / Calibration: real config, device capabilities, storage + degradation state. */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val degradation by viewModel.degradation.status.collectAsStateWithLifecycle()
    val cfg = viewModel.config
    val storage = viewModel.storage()

    Column(modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Settings / Calibration", style = MaterialTheme.typography.titleLarge)

        Section("Capture config")
        Kv("video", "${cfg.videoWidth}x${cfg.videoHeight} @ ${cfg.targetFps} fps")
        Kv("imu target", "${cfg.imuTargetHz} Hz")
        Kv("perception", "${cfg.perceptionHz} Hz")
        Kv("mcap seal", "${cfg.mcapChunkSealIntervalMs} ms")
        Kv("segment rollover", "${cfg.mcapSegmentRolloverBytes / (1024 * 1024)} MB")

        Section("Device capabilities")
        Kv("camera ts source", viewModel.cameraTimestampSource())
        Kv("imu direct channel", if (viewModel.directChannelSupported()) "supported" else "n/a")

        Section("Storage")
        Kv("free", storage.freeHuman)
        Kv("used by trips", storage.tripsHuman)

        Section("Degradation (live)")
        Kv("thermal", degradation.thermalState.name)
        Kv("perception", if (degradation.perceptionPaused) "PAUSED" else "active (cap ${degradation.perceptionHzCap} Hz)")
        Kv("render", if (degradation.renderEnabled) "on" else "off")
        Kv("storage action", degradation.storageAction.name)

        Text(
            "Recording + sync are never degraded by thermal or perception pressure.",
            style = MaterialTheme.typography.bodyMedium,
            color = MapPilotColors.OnSurfaceMuted,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun Section(t: String) = Text(
    t, style = MaterialTheme.typography.titleMedium,
    color = MapPilotColors.OnSurfaceMuted, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
)

@Composable
private fun Kv(k: String, v: String) = Row(
    Modifier.fillMaxWidth().padding(vertical = 3.dp),
    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
) {
    Text(k, style = TelemetryTextStyle, color = MapPilotColors.OnSurfaceMuted)
    Text(v, style = TelemetryTextStyle, color = MapPilotColors.OnSurface)
}
