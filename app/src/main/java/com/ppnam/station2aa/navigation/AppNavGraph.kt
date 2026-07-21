package com.ppnam.station2aa.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ppnam.station2aa.domain.model.MixingArea
import com.ppnam.station2aa.ui.login.LoginScreen
import com.ppnam.station2aa.ui.mixing.IngredientScanScreen
import com.ppnam.station2aa.ui.mixing.JobLookupScreen
import com.ppnam.station2aa.ui.mixing.MixingViewModel
import com.ppnam.station2aa.ui.mixing.board.MixingAreaPickerScreen
import com.ppnam.station2aa.ui.mixing.board.MixingBoardScreen
import com.ppnam.station2aa.ui.mixing.board.MixingBoardViewModel
import com.ppnam.station2aa.ui.rfid.RfidRecoveryScreen
import com.ppnam.station2aa.ui.session.SessionWatcher
import com.ppnam.station2aa.ui.settings.SettingsScreen

@Composable
fun AppNavGraph(navController: NavHostController = rememberNavController()) {
    SessionWatcher(navController)
    NavHost(navController = navController, startDestination = NavRoutes.LOGIN) {
        composable(NavRoutes.LOGIN) {
            LoginScreen(
                onLoggedIn = {
                    navController.navigate(NavRoutes.MIXING) {
                        popUpTo(NavRoutes.LOGIN) { inclusive = true }
                    }
                },
                onNavigateSettings = { navController.navigate(NavRoutes.SETTINGS) }
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
                    onSettings = { navController.navigate(NavRoutes.SETTINGS) },
                    onLogout = {
                        navController.navigate(NavRoutes.LOGIN) {
                            popUpTo(0)
                        }
                    },
                    onRfidLookup = {
                        viewModel.pauseScanning()
                        navController.navigate(NavRoutes.RFID_RECOVERY)
                    },
                    onOpenMixing = { navController.navigate(NavRoutes.mixingAreas()) },
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
                    onStartMixing = { collectionId ->
                        navController.navigate(NavRoutes.mixingAreas(collectionId))
                    },
                    onRfidLookup = {
                        viewModel.pauseScanning()
                        navController.navigate(NavRoutes.RFID_RECOVERY)
                    },
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
                )
            }
        }
        navigation(startDestination = NavRoutes.MIXING_AREAS, route = NavRoutes.MIXING_BOARD) {
            composable(
                NavRoutes.MIXING_AREAS,
                arguments = listOf(navArgument("pendingCollectionId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }),
            ) { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(NavRoutes.MIXING_BOARD)
                }
                val viewModel: MixingBoardViewModel = hiltViewModel(parentEntry)
                MixingAreaPickerScreen(
                    pendingCollectionId = backStackEntry.arguments?.getString("pendingCollectionId"),
                    onAreaChosen = { area -> navController.navigate(NavRoutes.mixingAreaBoard(area.wire)) },
                    onBack = { navController.popBackStack() },
                    onLogout = {
                        navController.navigate(NavRoutes.LOGIN) { popUpTo(0) }
                    },
                    viewModel = viewModel,
                )
            }
            composable(NavRoutes.MIXING_AREA_BOARD) { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(NavRoutes.MIXING_BOARD)
                }
                val viewModel: MixingBoardViewModel = hiltViewModel(parentEntry)
                val area = MixingArea.fromWire(backStackEntry.arguments?.getString("area"))
                if (area == null) {
                    // Only our own navigate() calls mint this route; a bad value is a bug.
                    navController.popBackStack()
                } else {
                    MixingBoardScreen(
                        area = area,
                        onBack = { navController.popBackStack() },
                        onLogout = {
                            navController.navigate(NavRoutes.LOGIN) { popUpTo(0) }
                        },
                        viewModel = viewModel,
                    )
                }
            }
        }
        composable(NavRoutes.RFID_RECOVERY) {
            RfidRecoveryScreen(
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
