package com.ppnam.station2aa.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ppnam.station2aa.ui.home.HomeScreen

@Composable
fun AppNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = NavRoutes.HOME) {
        composable(NavRoutes.HOME) {
            HomeScreen(
                onNavigateMixing = { navController.navigate(NavRoutes.JOB_LOOKUP) },
                onNavigateRajoo = { navController.navigate(NavRoutes.MACHINE_SELECT) },
                onNavigateRfidRecovery = { navController.navigate(NavRoutes.RFID_RECOVERY) },
                onNavigateDashboard = { navController.navigate(NavRoutes.DASHBOARD) }
            )
        }
        // Placeholders — filled in Tasks 10–14
        composable(NavRoutes.JOB_LOOKUP) { /* Task 10 */ }
        composable(NavRoutes.MACHINE_SELECT) { /* Task 12 */ }
        composable(NavRoutes.RFID_RECOVERY) { /* Task 13 */ }
        composable(NavRoutes.DASHBOARD) { /* Task 14 */ }
    }
}
