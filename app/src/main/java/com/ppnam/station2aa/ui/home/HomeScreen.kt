package com.ppnam.station2aa.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Factory
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ppnam.station2aa.ui.components.AppScaffold
import com.ppnam.station2aa.ui.theme.*

@Composable
fun HomeScreen(
    onNavigateMixing: () -> Unit,
    onNavigateRajoo: () -> Unit,
    onNavigateRfidRecovery: () -> Unit,
    onNavigateDashboard: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()

    AppScaffold(
        title = "PPNAM Station 2",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = null
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HomeTile(
                    title = "Mixing",
                    subtitle = "Pre-Mix Flow",
                    icon = Icons.Filled.Science,
                    tileColor = AmberPrimary,
                    height = 220.dp,
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateMixing
                )
                HomeTile(
                    title = "Rajoo",
                    subtitle = "Allocation",
                    icon = Icons.Filled.Factory,
                    tileColor = SuccessGreen,
                    height = 220.dp,
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateRajoo
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HomeTile(
                    title = "RFID Recovery",
                    subtitle = null,
                    icon = Icons.Filled.WifiTethering,
                    tileColor = InfoBlue,
                    height = 110.dp,
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateRfidRecovery
                )
                HomeTile(
                    title = "Dashboard",
                    subtitle = null,
                    icon = Icons.Filled.BarChart,
                    tileColor = IndigoAccent,
                    height = 110.dp,
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateDashboard
                )
            }
        }
    }
}

@Composable
private fun HomeTile(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    tileColor: Color,
    height: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.height(height),
        colors = CardDefaults.elevatedCardColors(containerColor = tileColor),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
    ) {
        if (subtitle != null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f)
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
