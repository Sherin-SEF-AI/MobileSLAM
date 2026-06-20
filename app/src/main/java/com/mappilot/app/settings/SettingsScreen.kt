package com.mappilot.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
import com.mappilot.core.common.config.CaptureConfig
import com.mappilot.core.common.config.DefaultConfigProvider
import com.mappilot.core.common.config.InferenceDelegate
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val configProvider: DefaultConfigProvider,
    private val sensorHub: SensorHub,
    private val storageManager: StorageManager,
    val degradation: DegradationController,
) : ViewModel() {
    fun config(): CaptureConfig = configProvider.current()

    /** Apply + persist a config change. Invalid combinations are rejected (no-op). */
    fun update(newConfig: CaptureConfig): Boolean =
        runCatching { configProvider.update(newConfig); true }.getOrDefault(false)

    fun storage(): StorageStatus = storageManager.status()
    fun directChannelSupported(): Boolean = runCatching { sensorHub.imu.supportsDirectChannel() }.getOrDefault(false)
    fun cameraTimestampSource(): String = sensorHub.camera.timestampSource.name
}

/** Settings / Calibration: editable capture config, device capabilities, storage + degradation state. */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val degradation by viewModel.degradation.status.collectAsStateWithLifecycle()
    var cfg by remember { mutableStateOf(viewModel.config()) }
    val storage = viewModel.storage()

    // Apply a candidate config; only commit to UI state if it validated + persisted.
    fun apply(candidate: CaptureConfig) {
        if (viewModel.update(candidate)) cfg = candidate
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Settings / Calibration", style = MaterialTheme.typography.titleLarge)

        Section("Capture config")
        OptionRow(
            "resolution",
            options = listOf(1280 to 720, 1920 to 1080, 3840 to 2160),
            selected = cfg.videoWidth to cfg.videoHeight,
            render = { "${it.first}x${it.second}" },
        ) { apply(cfg.copy(videoWidth = it.first, videoHeight = it.second)) }
        OptionRow("target fps", listOf(24, 30, 60), cfg.targetFps, { "$it" }) {
            apply(cfg.copy(targetFps = it))
        }
        OptionRow("imu Hz", listOf(100, 200, 400), cfg.imuTargetHz, { "$it" }) {
            apply(cfg.copy(imuTargetHz = it))
        }
        OptionRow("perception Hz", listOf(2, 4, 8, 15), cfg.perceptionHz, { "$it" }) {
            apply(cfg.copy(perceptionHz = it))
        }
        OptionRow(
            "segment rollover",
            options = listOf(256L, 512L, 1024L),
            selected = cfg.mcapSegmentRolloverBytes / (1024 * 1024),
            render = { "$it MB" },
        ) { apply(cfg.copy(mcapSegmentRolloverBytes = it * 1024 * 1024)) }

        Section("Perception backend")
        OptionRow(
            "inference delegate",
            options = InferenceDelegate.entries.toList(),
            selected = cfg.inferenceDelegate,
            render = { it.name },
        ) { apply(cfg.copy(inferenceDelegate = it)) }
        Text(
            "AUTO probes the GPU delegate, then falls back to CPU/XNNPACK. NNAPI is legacy " +
                "(deprecated on Android 15+). Takes effect on the next recording.",
            style = MaterialTheme.typography.bodySmall,
            color = MapPilotColors.OnSurfaceMuted,
            modifier = Modifier.padding(top = 4.dp),
        )

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
private fun <T> OptionRow(
    label: String,
    options: List<T>,
    selected: T,
    render: (T) -> String,
    onSelect: (T) -> Unit,
) = Row(
    Modifier.fillMaxWidth().padding(vertical = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(label, style = TelemetryTextStyle, color = MapPilotColors.OnSurfaceMuted)
    Row {
        options.forEach { opt ->
            val sel = opt == selected
            Text(
                render(opt),
                style = TelemetryTextStyle,
                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                color = if (sel) MaterialTheme.colorScheme.onPrimary else MapPilotColors.OnSurface,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel) MaterialTheme.colorScheme.primary else MapPilotColors.SurfaceVariant)
                    .clickable { onSelect(opt) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
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
    horizontalArrangement = Arrangement.SpaceBetween,
) {
    Text(k, style = TelemetryTextStyle, color = MapPilotColors.OnSurface)
    Text(v, style = TelemetryTextStyle, color = MapPilotColors.OnSurface)
}
