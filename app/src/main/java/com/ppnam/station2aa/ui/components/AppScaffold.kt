package com.ppnam.station2aa.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ppnam.station2aa.domain.repository.MqttConnectionState
import com.ppnam.station2aa.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    connectionState: MqttConnectionState,
    pendingCount: Int,
    onBack: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    val (dotColor, statusLabel) = when (connectionState) {
        MqttConnectionState.CONNECTED    -> SuccessGreen to "Connected"
        MqttConnectionState.RECONNECTING -> AmberPrimary to "Reconnecting"
        MqttConnectionState.DISCONNECTED ->
            DangerRed to if (pendingCount > 0) "Offline — $pendingCount queued" else "Offline"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = AmberPrimary
                            )
                        }
                    }
                },
                actions = {
                    if (onSettings != null) {
                        IconButton(onClick = onSettings) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Settings",
                                tint = TextMuted
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clip(RoundedCornerShape(50))
                            .background(dotColor.copy(alpha = 0.12f))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Canvas(modifier = Modifier.size(6.dp)) {
                                drawCircle(color = dotColor)
                            }
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = dotColor
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GraphiteSurface
                )
            )
        },
        containerColor = GraphiteBackground,
        content = content
    )
}
