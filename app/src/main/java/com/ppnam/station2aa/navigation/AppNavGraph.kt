package com.ppnam.station2aa.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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
import com.ppnam.station2aa.ui.components.UpgradeRequiredGate
import com.ppnam.station2aa.ui.rfid.RfidRecoveryScreen
import com.ppnam.station2aa.ui.session.SessionWatcher
import com.ppnam.station2aa.ui.settings.SettingsScreen

/** Unwraps the Compose LocalContext to the hosting Activity, or null if it isn't one. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun AppNavGraph(navController: NavHostController = rememberNavController()) {
    SessionWatcher(navController)
    NavHost(navController = navController, startDestination = NavRoutes.LOGIN) {
        composable(NavRoutes.LOGIN) {
            // LocalActivity only exists from activity-compose 1.10; this project is on 1.9.0.
            val activity = LocalContext.current.findActivity()
            LoginScreen(
                onLoggedIn = {
                    navController.navigate(NavRoutes.MIXING) {
                        popUpTo(NavRoutes.LOGIN) { inclusive = true }
                    }
                },
                onNavigateSettings = { navController.navigate(NavRoutes.SETTINGS) },
                // Login is the start destination — there is no back stack to pop, so leaving
                // means finishing the Activity. Only reached via the explicit confirm dialog.
                onExitApp = { activity?.finish() },
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
                val activity = LocalContext.current.findActivity()
                JobLookupScreen(
                    onJobFound = { orderNo -> navController.navigate(NavRoutes.ingredientScan(orderNo)) },
                    onSettings = { navController.navigate(NavRoutes.SETTINGS) },
                    // Navigation on logout is SessionWatcher's job alone — see the comment on
                    // MixingAreaPickerScreen's onLogout below.
                    onLogout = {},
                    onRfidLookup = {
                        viewModel.pauseScanning()
                        navController.navigate(NavRoutes.RFID_RECOVERY)
                    },
                    onOpenMixing = { navController.navigate(NavRoutes.mixingAreas()) },
                    // Start of the post-login graph: nothing to pop, so leaving finishes the
                    // Activity. Only reached via the explicit confirm dialog.
                    onExitApp = { activity?.finish() },
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
                    // Navigation on logout is SessionWatcher's job alone (it reacts to the
                    // session going null, which authUseCase.logout() causes before this event
                    // even fires) — a second navigate(LOGIN){popUpTo(0)} here raced it non-
                    // deterministically, occasionally double-tearing-down/recreating Login.
                    onLogout = {},
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
                    // Navigation must run as a side effect, not directly in the composable body —
                    // calling popBackStack() here unconditionally on every recomposition of this
                    // branch is exactly the unsafe pattern Navigation-Compose warns against.
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    MixingBoardScreen(
                        area = area,
                        onBack = { navController.popBackStack() },
                        // Navigation on logout is SessionWatcher's job alone — see the comment on
                        // MixingAreaPickerScreen's onLogout above.
                        onLogout = {},
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
    UpgradeRequiredGate()
}
