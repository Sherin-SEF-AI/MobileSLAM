package com.mappilot.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.mappilot.app.ui.navigation.MapPilotNavHost
import com.mappilot.app.ui.permissions.PermissionGate
import com.mappilot.app.ui.theme.MapPilotTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MapPilotTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PermissionGate {
                        MapPilotNavHost()
                    }
                }
            }
        }
    }
}
