package com.mappilot.app.capture

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mappilot.app.ui.theme.MapPilotColors
import com.mappilot.app.ui.theme.TelemetryTextStyle
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import com.mappilot.core.model.Constellation
import com.mappilot.core.model.RecordingState
import com.mappilot.core.model.StreamHealth
import com.mappilot.core.model.TimestampSource

/**
 * Phase-1 Capture screen: a live camera preview behind a data-dense HUD driven
 * entirely by real, measured telemetry from the SyncEngine and sensor sources.
 */
@Composable
fun CaptureScreen(
    modifier: Modifier = Modifier,
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val hud by viewModel.hud.collectAsStateWithLifecycle()
    val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize().background(MapPilotColors.Background)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            viewModel.onPreviewSurfaceAvailable(holder.surface)
                        }

                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) = Unit

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            viewModel.onPreviewSurfaceDestroyed()
                        }
                    })
                }
            },
        )

        HudOverlay(
            hud = hud,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(12.dp),
        )

        RecordControl(
            state = recordingState,
            onToggle = viewModel::toggleRecording,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
        )
    }
}

/** The single, unmistakable record control. Red square when recording, ring when idle. */
@Composable
private fun RecordControl(
    state: RecordingState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recording = state == RecordingState.RECORDING || state == RecordingState.STARTING
    Box(
        modifier = modifier
            .size(72.dp)
            .clip(CircleShape)
            .border(3.dp, MapPilotColors.OnSurface, CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(if (recording) 28.dp else 56.dp)
                .clip(if (recording) RoundedCornerShape(6.dp) else CircleShape)
                .background(MapPilotColors.Recording),
        )
    }
}

@Composable
private fun HudOverlay(hud: CaptureHudState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xCC0A0A0A))
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        StatusBar(hud)

        Section("CAMERA")
        val cam = hud.camera
        if (cam.available) {
            Telemetry("res", "${cam.width}x${cam.height}")
            Telemetry("fps", fmt(cam.fps, "%.1f"))
            Telemetry("ts_src", cam.timestampSource.name, warn = cam.timestampSource == TimestampSource.UNKNOWN)
            Telemetry("exposure", cam.exposureMs?.let { fmt(it, "%.2f") + " ms" } ?: "—")
            Telemetry("iso", cam.iso?.toString() ?: "—")
            Telemetry("intrinsics", if (cam.hasIntrinsics) "calibrated" else "UNAVAILABLE", warn = !cam.hasIntrinsics)
        } else {
            Telemetry("status", "UNAVAILABLE", warn = true)
        }

        Section("IMU")
        Telemetry("accel", fmt(hud.imu.accelHz, "%.0f") + " Hz", warn = hud.imu.accelHz in 0.1..99.9)
        Telemetry("gyro", fmt(hud.imu.gyroHz, "%.0f") + " Hz", warn = hud.imu.gyroHz in 0.1..99.9)
        Telemetry("mag", fmt(hud.imu.magHz, "%.0f") + " Hz")
        Telemetry("rotation", fmt(hud.imu.rotationHz, "%.0f") + " Hz")
        Telemetry("dropped", hud.imu.droppedTotal.toString(), warn = hud.imu.droppedTotal > 0)
        Telemetry("direct_ch", if (hud.imu.directChannelSupported) "supported" else "n/a")

        Section("GNSS")
        val g = hud.gnss
        Telemetry("fix", if (g.hasFix) "LOCK" else "no fix", warn = !g.hasFix)
        Telemetry("sats", "${g.satellitesUsed}/${g.satellitesVisible} used/vis")
        Telemetry("mean_cn0", fmt(g.meanCn0.toDouble(), "%.1f") + " dBHz")
        if (g.perConstellation.isNotEmpty()) {
            Telemetry("constels", g.perConstellation.entries.joinToString(" ") {
                "${abbrev(it.key)}:${it.value}"
            })
        }
        g.fix?.let { fix ->
            Telemetry("lat", fmt(fix.latitude, "%.6f"))
            Telemetry("lon", fmt(fix.longitude, "%.6f"))
            Telemetry("h_acc", if (fix.hAccuracyM >= 0) fmt(fix.hAccuracyM.toDouble(), "%.1f") + " m" else "—")
        }

        Section("SYNC")
        hud.streams.forEach { StreamRow(it) }

        if (hud.warnings.isNotEmpty()) {
            Section("WARNINGS")
            hud.warnings.forEach {
                Text(
                    text = "${it.kind} [${it.stream}] ${it.detail}",
                    style = TelemetryTextStyle,
                    color = MapPilotColors.Degraded,
                )
            }
        }
    }
}

@Composable
private fun StatusBar(hud: CaptureHudState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(if (hud.running) MapPilotColors.TrackingLock else MapPilotColors.Idle),
        )
        Text(
            text = if (hud.running) "  CAPTURING" else "  IDLE",
            style = TelemetryTextStyle,
            color = if (hud.running) MapPilotColors.TrackingLock else MapPilotColors.OnSurfaceMuted,
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        text = "── $title",
        style = TelemetryTextStyle,
        color = MapPilotColors.OnSurfaceMuted,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun Telemetry(label: String, value: String, warn: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = TelemetryTextStyle, color = MapPilotColors.OnSurfaceMuted)
        Text(
            value,
            style = TelemetryTextStyle,
            color = if (warn) MapPilotColors.Degraded else MapPilotColors.OnSurface,
        )
    }
}

@Composable
private fun StreamRow(s: StreamHealth) {
    val driftWarn = kotlin.math.abs(s.driftNs) > 5_000_000
    Telemetry(
        label = s.streamId,
        value = "${fmt(s.rateHz, "%.0f")}Hz off=${us(s.appliedOffsetNs)} " +
            "drift=${us(s.driftNs)} lat=${us(s.latencyNs)} drp=${s.samplesDropped}",
        warn = driftWarn || s.samplesDropped > 0,
    )
}

private fun fmt(v: Double, pattern: String): String =
    if (v.isNaN()) "—" else String.format(pattern, v)

/** Nanoseconds → microseconds, compact. */
private fun us(ns: Long): String = if (ns < 0) "—" else "${ns / 1000}us"

private fun abbrev(c: Constellation): String = when (c) {
    Constellation.GPS -> "GPS"
    Constellation.GLONASS -> "GLO"
    Constellation.GALILEO -> "GAL"
    Constellation.BEIDOU -> "BDS"
    Constellation.QZSS -> "QZS"
    Constellation.IRNSS -> "NAV"
    Constellation.SBAS -> "SBA"
    Constellation.UNKNOWN -> "UNK"
}
