package com.ppnam.station2aa.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.ppnam.station2aa.ui.dashboard.DashboardScreen
import com.ppnam.station2aa.ui.home.HomeScreen
import com.ppnam.station2aa.ui.login.LoginScreen
import com.ppnam.station2aa.ui.mixing.HopperScanScreen
import com.ppnam.station2aa.ui.mixing.IngredientScanScreen
import com.ppnam.station2aa.ui.mixing.JobLookupScreen
import com.ppnam.station2aa.ui.mixing.MixingViewModel
import com.ppnam.station2aa.ui.mixing.PreMixCompleteScreen
import com.ppnam.station2aa.ui.rajoo.MachineSelectScreen
import com.ppnam.station2aa.ui.rajoo.PalletAllocScreen
import com.ppnam.station2aa.ui.rfid.RfidRecoveryScreen
import com.ppnam.station2aa.ui.settings.SettingsScreen

@Composable
fun AppNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = NavRoutes.LOGIN) {
        composable(NavRoutes.LOGIN) {
            LoginScreen(
                onLoggedIn = {
                    navController.navigate(NavRoutes.HOME) {
                        popUpTo(NavRoutes.LOGIN) { inclusive = true }
                    }
                },
                onNavigateSettings = { navController.navigate(NavRoutes.SETTINGS) }
            )
        }
        composable(NavRoutes.HOME) {
            HomeScreen(
                onNavigateMixing = { navController.navigate(NavRoutes.MIXING) },
                onNavigateRajoo = { navController.navigate(NavRoutes.MACHINE_SELECT) },
                onNavigateRfidRecovery = { navController.navigate(NavRoutes.RFID_RECOVERY) },
                onNavigateDashboard = { navController.navigate(NavRoutes.DASHBOARD) },
                onNavigateSettings = { navController.navigate(NavRoutes.SETTINGS) },
                onLogout = {
                    navController.navigate(NavRoutes.LOGIN) {
                        popUpTo(0)
                    }
                }
            )
        }
        composable(NavRoutes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        navigation(startDestination = NavRoutes.JOB_LOOKUP, route = NavRoutes.MIXING) {
            composable(NavRoutes.JOB_LOOKUP) { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(NavRoutes.MIXING)
                }
                val viewModel: MixingViewModel = hiltViewModel(parentEntry)
                JobLookupScreen(
                    onJobFound = { orderNo -> navController.navigate(NavRoutes.ingredientScan(orderNo)) },
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
                )
            }
            composable(NavRoutes.INGREDIENT_SCAN) { backStackEntry ->
                val orderNo = backStackEntry.arguments?.getString("orderNo") ?: return@composable
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(NavRoutes.MIXING)
                }
                val viewModel: MixingViewModel = hiltViewModel(parentEntry)
                IngredientScanScreen(
                    orderNo = orderNo,
                    onProceedToHopperScan = { navController.navigate(NavRoutes.hopperScan(orderNo)) },
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
                )
            }
            composable(NavRoutes.HOPPER_SCAN) { backStackEntry ->
                val orderNo = backStackEntry.arguments?.getString("orderNo") ?: return@composable
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(NavRoutes.MIXING)
                }
                val viewModel: MixingViewModel = hiltViewModel(parentEntry)
                HopperScanScreen(
                    orderNo = orderNo,
                    onProceed = { navController.navigate(NavRoutes.premixComplete(orderNo)) },
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
                )
            }
            composable(NavRoutes.PREMIX_COMPLETE) { backStackEntry ->
                val orderNo = backStackEntry.arguments?.getString("orderNo") ?: return@composable
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(NavRoutes.MIXING)
                }
                val viewModel: MixingViewModel = hiltViewModel(parentEntry)
                PreMixCompleteScreen(
                    orderNo = orderNo,
                    onCompleted = {
                        navController.navigate(NavRoutes.HOME) {
                            popUpTo(NavRoutes.HOME) { inclusive = true }
                        }
                    },
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
                )
            }
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
            RfidRecoveryScreen(
                onDone = {
                    navController.navigate(NavRoutes.HOME) {
                        popUpTo(NavRoutes.HOME) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(NavRoutes.DASHBOARD) {
            DashboardScreen(onBack = { navController.popBackStack() })
        }
    }
}
