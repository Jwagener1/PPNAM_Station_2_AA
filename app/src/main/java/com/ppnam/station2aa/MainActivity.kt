package com.ppnam.station2aa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
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
            val metrics = WindowMetricsCalculator.getOrCreate()
                .computeCurrentWindowMetrics(this)
            val widthPx = metrics.bounds.width()
            val heightPx = metrics.bounds.height()
            
            val density = LocalDensity.current
            val windowSizeDp = with(density) {
                DpSize(widthPx.toDp(), heightPx.toDp())
            }

            PPNAMStation2AATheme {
                CompositionLocalProvider(LocalWindowSize provides windowSizeDp) {
                    AppNavGraph()
                }
            }
        }
    }
}
