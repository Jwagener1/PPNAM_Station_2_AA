package com.ppnam.station2aa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ppnam.station2aa.domain.repository.MqttConnectionState

@Composable
fun ConnectionStatusBar(state: MqttConnectionState, pendingCount: Int) {
    val (color, label) = when (state) {
        MqttConnectionState.CONNECTED -> Color(0xFF2E7D32) to "Connected"
        MqttConnectionState.RECONNECTING -> Color(0xFFF9A825) to "Reconnecting…"
        MqttConnectionState.DISCONNECTED ->
            Color(0xFFC62828) to if (pendingCount > 0) "Offline — $pendingCount queued" else "Offline"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Color.White, fontSize = 12.sp)
    }
}
