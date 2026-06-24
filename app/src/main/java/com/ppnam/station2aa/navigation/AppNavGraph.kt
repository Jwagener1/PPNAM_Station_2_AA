package com.ppnam.station2aa.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ppnam.station2aa.ui.home.HomeScreen
import com.ppnam.station2aa.ui.mixing.JobLookupScreen

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
        composable(NavRoutes.JOB_LOOKUP) {
            JobLookupScreen(onJobFound = { orderNo ->
                navController.navigate(NavRoutes.ingredientScan(orderNo))
            })
        }
        composable(NavRoutes.INGREDIENT_SCAN) { backStack ->
            @Suppress("UNUSED_VARIABLE")
            val orderNo = backStack.arguments?.getString("orderNo") ?: return@composable
            // Task 11
        }
        composable(NavRoutes.MIXER_CODE) { backStack ->
            @Suppress("UNUSED_VARIABLE")
            val orderNo = backStack.arguments?.getString("orderNo") ?: return@composable
            // Task 11
        }
        composable(NavRoutes.PREMIX_COMPLETE) { backStack ->
            @Suppress("UNUSED_VARIABLE")
            val orderNo = backStack.arguments?.getString("orderNo") ?: return@composable
            // Task 11
        }
        composable(NavRoutes.MACHINE_SELECT) { /* Task 12 */ }
        composable(NavRoutes.RFID_RECOVERY) { /* Task 13 */ }
        composable(NavRoutes.DASHBOARD) { /* Task 14 */ }
    }
}
