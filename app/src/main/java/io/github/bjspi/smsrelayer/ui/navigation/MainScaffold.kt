package io.github.bjspi.smsrelayer.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material.icons.filled.Troubleshoot
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.bjspi.smsrelayer.R
import io.github.bjspi.smsrelayer.ui.diagnostics.DiagnosticsScreen
import io.github.bjspi.smsrelayer.ui.logs.LogsScreen
import io.github.bjspi.smsrelayer.ui.settings.SettingsScreen
import io.github.bjspi.smsrelayer.ui.status.StatusScreen

private enum class MainTab(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Status("status", R.string.tab_status, Icons.Filled.SpaceDashboard),
    Logs("logs", R.string.tab_logs, Icons.AutoMirrored.Filled.Article),
    Diagnostics("diagnostics", R.string.tab_diagnostics, Icons.Filled.Troubleshoot),
    Settings("settings", R.string.tab_settings, Icons.Filled.Settings),
}

@Composable
fun MainScaffold() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = MainTab.Status.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(MainTab.Status.route) { StatusScreen() }
            composable(MainTab.Logs.route) { LogsScreen() }
            composable(MainTab.Diagnostics.route) { DiagnosticsScreen() }
            composable(MainTab.Settings.route) { SettingsScreen() }
        }
    }
}
