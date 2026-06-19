package com.mappilot.app.ui.permissions

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

/**
 * Gates the app behind the runtime permissions capture requires. Until all are
 * granted, the UI shows an explicit request screen — no silent degradation.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionGate(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val permissions = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val state = rememberMultiplePermissionsState(permissions)

    if (state.allPermissionsGranted) {
        content()
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "MapPilot needs Camera, Location, Microphone and Notification access to capture synchronized sensor data.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = { state.launchMultiplePermissionRequest() },
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Text("Grant permissions")
            }
        }
    }
}
