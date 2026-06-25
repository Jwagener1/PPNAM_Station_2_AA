package com.ppnam.station2aa.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ppnam.station2aa.ui.home.HomeScreen
import com.ppnam.station2aa.ui.mixing.IngredientScanScreen
import com.ppnam.station2aa.ui.mixing.JobLookupScreen
import com.ppnam.station2aa.ui.mixing.MixerCodeScreen
import com.ppnam.station2aa.ui.mixing.PreMixCompleteScreen
import com.ppnam.station2aa.ui.rajoo.MachineSelectScreen
import com.ppnam.station2aa.ui.rajoo.PalletAllocScreen
import com.ppnam.station2aa.ui.dashboard.DashboardScreen
import com.ppnam.station2aa.ui.rfid.RfidRecoveryScreen

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
            JobLookupScreen(
                onJobFound = { orderNo -> navController.navigate(NavRoutes.ingredientScan(orderNo)) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(NavRoutes.INGREDIENT_SCAN) { backStack ->
            val orderNo = backStack.arguments?.getString("orderNo") ?: return@composable
            IngredientScanScreen(
                orderNo = orderNo,
                onProceedToMixerCode = { navController.navigate(NavRoutes.mixerCode(orderNo)) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(NavRoutes.MIXER_CODE) { backStack ->
            val orderNo = backStack.arguments?.getString("orderNo") ?: return@composable
            MixerCodeScreen(
                orderNo = orderNo,
                onProceed = { navController.navigate(NavRoutes.premixComplete(orderNo)) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(NavRoutes.PREMIX_COMPLETE) { backStack ->
            val orderNo = backStack.arguments?.getString("orderNo") ?: return@composable
            PreMixCompleteScreen(
                orderNo = orderNo,
                onCompleted = {
                    navController.navigate(NavRoutes.HOME) {
                        popUpTo(NavRoutes.HOME) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(NavRoutes.MACHINE_SELECT) {
            MachineSelectScreen(
                onMachineSelected = { machineCode -> navController.navigate(NavRoutes.palletAlloc(machineCode)) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(NavRoutes.PALLET_ALLOC) { backStack ->
            val machineCode = backStack.arguments?.getString("machineCode") ?: return@composable
            PalletAllocScreen(
                machineCode = machineCode,
                onDone = {
                    navController.navigate(NavRoutes.HOME) {
                        popUpTo(NavRoutes.HOME) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(NavRoutes.RFID_RECOVERY) {
            RfidRecoveryScreen(onDone = {
                navController.navigate(NavRoutes.HOME) {
                    popUpTo(NavRoutes.HOME) { inclusive = true }
                }
            })
        }
        composable(NavRoutes.DASHBOARD) {
            DashboardScreen()
        }
    }
}
