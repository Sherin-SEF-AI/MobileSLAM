package com.mappilot.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mappilot.app.assets.AssetBrowserScreen
import com.mappilot.app.capture.CaptureScreen
import com.mappilot.app.sessions.MapExplorerScreen
import com.mappilot.app.sessions.SessionDetailScreen
import com.mappilot.app.sessions.SessionsScreen
import com.mappilot.app.export.ExportScreen
import com.mappilot.app.jobs.JobsScreen
import com.mappilot.app.settings.SettingsScreen

@Composable
fun MapPilotNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { top ->
                    val selected = currentRoute == top.destination.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(top.destination.route) {
                                popUpTo(MapPilotDestination.Capture.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(top.icon, contentDescription = top.label) },
                        label = { Text(top.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = MapPilotDestination.Capture.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(MapPilotDestination.Capture.route) { CaptureScreen() }
            composable(MapPilotDestination.Sessions.route) {
                SessionsScreen(onOpenTrip = { id -> navController.navigate(MapPilotDestination.sessionDetailRoute(id)) })
            }
            composable(
                route = MapPilotDestination.SessionDetail.route,
                arguments = listOf(navArgument("tripId") { type = NavType.LongType }),
            ) { entry ->
                SessionDetailScreen(
                    tripId = entry.arguments?.getLong("tripId") ?: -1L,
                    onExport = { id -> navController.navigate(MapPilotDestination.exportRoute(id)) },
                )
            }
            composable(MapPilotDestination.MapExplorer.route) { MapExplorerScreen() }
            composable(MapPilotDestination.AssetBrowser.route) { AssetBrowserScreen() }
            composable(
                route = MapPilotDestination.Export.route,
                arguments = listOf(navArgument("tripId") { type = NavType.LongType }),
            ) { entry ->
                ExportScreen(tripId = entry.arguments?.getLong("tripId") ?: -1L)
            }
            composable(MapPilotDestination.Jobs.route) { JobsScreen() }
            composable(MapPilotDestination.Settings.route) { SettingsScreen() }
        }
    }
}
