package com.ppnam.station2aa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.window.layout.WindowMetricsCalculator
import com.ppnam.station2aa.navigation.AppNavGraph
import com.ppnam.station2aa.ui.theme.PPNAMStation2AATheme
import dagger.hilt.android.AndroidEntryPoint

val LocalWindowSize = compositionLocalOf { DpSize.Unspecified }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val configuration = LocalConfiguration.current
            val density = LocalDensity.current
            // Recomputed only when the configuration actually changes (rotation, multi-window
            // resize, font scale) instead of on every recomposition of this root scope, which
            // was issuing a WindowManager/Binder round trip on each pass.
            val windowSizeDp = remember(configuration) {
                val metrics = WindowMetricsCalculator.getOrCreate()
                    .computeCurrentWindowMetrics(this)
                with(density) {
                    DpSize(metrics.bounds.width().toDp(), metrics.bounds.height().toDp())
                }
            }

            PPNAMStation2AATheme {
                CompositionLocalProvider(LocalWindowSize provides windowSizeDp) {
                    AppNavGraph()
                }
            }
        }
    }
}
