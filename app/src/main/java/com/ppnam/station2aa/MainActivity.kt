package com.ppnam.station2aa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ppnam.station2aa.navigation.AppNavGraph
import com.ppnam.station2aa.ui.theme.PPNAMStation2AATheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PPNAMStation2AATheme {
                AppNavGraph()
            }
        }
    }
}
