package com.ppnam.station2aa.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
import com.ppnam.station2aa.LocalWindowSize
import androidx.hilt.navigation.compose.hiltViewModel
import com.ppnam.station2aa.ui.components.AppScaffold
import com.ppnam.station2aa.ui.theme.*

@Composable
fun HomeScreen(
    onNavigateMixing: () -> Unit,
    onNavigateRajoo: () -> Unit,
    onNavigateRfidRecovery: () -> Unit,
    onNavigateDashboard: () -> Unit,
    onNavigateSettings: () -> Unit,
    onLogout: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val session by viewModel.session.collectAsState()
    val windowSize = LocalWindowSize.current
    val isExpanded = (windowSize != androidx.compose.ui.unit.DpSize.Unspecified) && (windowSize.width > 600.dp)

    LaunchedEffect(Unit) {
        viewModel.logoutEvent.collect { onLogout() }
    }

    AppScaffold(
        title = "PPNAM Station 2",
        status = connectionStatus,
        onBack = null,
        onSettings = onNavigateSettings,
        operatorName = session?.operatorName,
        operatorRole = session?.role,
        onLogout = viewModel::logout
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isExpanded) {
                // Tablet layout: All tiles in one row or more spread out
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HomeTile(
                        title = "Mixing",
                        subtitle = "Pre-Mix Flow",
                        icon = Icons.Filled.Science,
                        accentColor = AmberPrimary,
                        height = 220.dp,
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateMixing
                    )
                    HomeTile(
                        title = "Rajoo",
                        subtitle = "Allocation",
                        icon = Icons.Filled.Factory,
                        accentColor = SuccessGreen,
                        height = 220.dp,
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateRajoo
                    )
                    HomeTile(
                        title = "RFID Recovery",
                        subtitle = null,
                        icon = Icons.Filled.WifiTethering,
                        accentColor = InfoBlue,
                        height = 220.dp,
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateRfidRecovery
                    )
                    HomeTile(
                        title = "Dashboard",
                        subtitle = null,
                        icon = Icons.Filled.BarChart,
                        accentColor = IndigoAccent,
                        height = 220.dp,
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateDashboard
                    )
                }
            } else {
                // Phone layout: 2x2 grid (as before)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HomeTile(
                        title = "Mixing",
                        subtitle = "Pre-Mix Flow",
                        icon = Icons.Filled.Science,
                        accentColor = AmberPrimary,
                        height = 220.dp,
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateMixing
                    )
                    HomeTile(
                        title = "Rajoo",
                        subtitle = "Allocation",
                        icon = Icons.Filled.Factory,
                        accentColor = SuccessGreen,
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
                        accentColor = InfoBlue,
                        height = 110.dp,
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateRfidRecovery
                    )
                    HomeTile(
                        title = "Dashboard",
                        subtitle = null,
                        icon = Icons.Filled.BarChart,
                        accentColor = IndigoAccent,
                        height = 110.dp,
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateDashboard
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeTile(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    accentColor: Color,
    height: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(height),
        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.25f))
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(4.dp)
                    .background(accentColor)
            )
            if (subtitle != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(40.dp)
                    )
                    Column {
                        Text(
                            text = title.uppercase(),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            ),
                            color = TextPrimary
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}
