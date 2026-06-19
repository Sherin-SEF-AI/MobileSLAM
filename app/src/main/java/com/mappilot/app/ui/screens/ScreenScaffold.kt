package com.mappilot.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mappilot.app.ui.theme.TelemetryTextStyle

/**
 * Phase-0 screen shell. Each screen declares what subsystem it will host and in
 * which phase it lands — an honest "not yet built" state, never fabricated data.
 */
@Composable
fun PhaseStubScreen(
    title: String,
    subtitle: String,
    landsInPhase: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "■ pending $landsInPhase",
            style = TelemetryTextStyle,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}
