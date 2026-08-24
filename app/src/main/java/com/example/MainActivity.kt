package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoCameraFront
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.ElegantDarkBackground
import com.example.ui.theme.ElegantDarkOnPrimary
import com.example.ui.theme.ElegantDarkPrimary
import com.example.ui.theme.ElegantDarkSurfaceVariant
import com.example.ui.theme.ElegantDarkTertiary
import com.example.ui.theme.ZestoTheme
import com.example.zesto.ui.DiagnosticsScreen
import com.example.zesto.ui.PreviewScreen
import com.example.zesto.ui.StreamConfigScreen
import com.example.zesto.ui.TargetCompatibilityScreen
import com.example.zesto.ui.ZestoTab
import com.example.zesto.ui.ZestoViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: ZestoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZestoTheme {
                ZestoApp(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZestoApp(
    viewModel: ZestoViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val infiniteTransition = rememberInfiniteTransition(label = "spin_transition")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3500, easing = LinearEasing)
        ),
        label = "spin_angle"
    )

    LaunchedEffect(uiState.userNoticeMessage) {
        uiState.userNoticeMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.dismissUserNotice()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ElegantDarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Logo Box matching HTML specification
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(ElegantDarkPrimary),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .rotate(if (uiState.isConnected || uiState.isDecoding) rotationAngle else 0f)
                                        .border(
                                            width = 3.dp,
                                            color = ElegantDarkOnPrimary,
                                            shape = CircleShape
                                        )
                                )
                            }
                            Text(
                                text = "Zesto",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }

                        // Status Badge with Phase 1 & Glowing Emerald Status Dot
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(ElegantDarkSurfaceVariant)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = if (uiState.isServiceRunning) "SERVICE RUNNING" else if (uiState.isConnected) "LIVE STREAM" else "READY",
                                    color = if (uiState.isServiceRunning || uiState.isConnected) ElegantDarkTertiary else ElegantDarkPrimary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (uiState.isConnected || uiState.isDecoding) ElegantDarkTertiary
                                        else if (uiState.isConnecting) Color(0xFFFBBF24)
                                        else Color(0xFF64748B)
                                    )
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ElegantDarkBackground,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = ElegantDarkSurfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                NavigationBarItem(
                    selected = uiState.selectedTab == ZestoTab.STREAM_CONFIG,
                    onClick = { viewModel.selectTab(ZestoTab.STREAM_CONFIG) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Config") },
                    label = { Text("Config") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ElegantDarkOnPrimary,
                        selectedTextColor = ElegantDarkPrimary,
                        indicatorColor = ElegantDarkPrimary,
                        unselectedIconColor = MaterialTheme.colorScheme.outline,
                        unselectedTextColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.testTag("tab_config")
                )
                NavigationBarItem(
                    selected = uiState.selectedTab == ZestoTab.STREAM_PREVIEW,
                    onClick = { viewModel.selectTab(ZestoTab.STREAM_PREVIEW) },
                    icon = { Icon(Icons.Default.Preview, contentDescription = "Preview") },
                    label = { Text("Preview") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ElegantDarkOnPrimary,
                        selectedTextColor = ElegantDarkPrimary,
                        indicatorColor = ElegantDarkPrimary,
                        unselectedIconColor = MaterialTheme.colorScheme.outline,
                        unselectedTextColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.testTag("tab_preview")
                )
                NavigationBarItem(
                    selected = uiState.selectedTab == ZestoTab.DIAGNOSTICS,
                    onClick = { viewModel.selectTab(ZestoTab.DIAGNOSTICS) },
                    icon = { Icon(Icons.Default.Assessment, contentDescription = "Diagnostics") },
                    label = { Text("Diagnostics") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ElegantDarkOnPrimary,
                        selectedTextColor = ElegantDarkPrimary,
                        indicatorColor = ElegantDarkPrimary,
                        unselectedIconColor = MaterialTheme.colorScheme.outline,
                        unselectedTextColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.testTag("tab_diagnostics")
                )
                NavigationBarItem(
                    selected = uiState.selectedTab == ZestoTab.TARGET_COMPAT,
                    onClick = { viewModel.selectTab(ZestoTab.TARGET_COMPAT) },
                    icon = { Icon(Icons.Default.VideoCameraFront, contentDescription = "Target & API") },
                    label = { Text("Target & API") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ElegantDarkOnPrimary,
                        selectedTextColor = ElegantDarkPrimary,
                        indicatorColor = ElegantDarkPrimary,
                        unselectedIconColor = MaterialTheme.colorScheme.outline,
                        unselectedTextColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.testTag("tab_target")
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(ElegantDarkBackground)
        ) {
            when (uiState.selectedTab) {
                ZestoTab.STREAM_CONFIG -> {
                    StreamConfigScreen(
                        uiState = uiState,
                        onUrlChange = viewModel::updateStreamUrl,
                        onProtocolChange = viewModel::updateTransportProtocol,
                        onResolutionChange = viewModel::updateResolution,
                        onFpsChange = viewModel::updateTargetFps,
                        onTestConnection = viewModel::testConnection,
                        onConnect = viewModel::connectStream,
                        onDisconnect = viewModel::disconnectStream
                    )
                }
                ZestoTab.STREAM_PREVIEW -> {
                    PreviewScreen(
                        uiState = uiState,
                        onStartDecoder = viewModel::startDecoderAndPipeline,
                        onStopDecoder = viewModel::stopDecoderAndPipeline
                    )
                }
                ZestoTab.DIAGNOSTICS -> {
                    DiagnosticsScreen(
                        uiState = uiState,
                        onExportLog = viewModel::exportDiagnosticsLog,
                        onDismissExportDialog = viewModel::clearExportedLogDialog
                    )
                }
                ZestoTab.TARGET_COMPAT -> {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    TargetCompatibilityScreen(
                        uiState = uiState,
                        onSelectProfile = viewModel::selectTargetProfile,
                        onSearchChange = viewModel::updateProfileSearchQuery,
                        onLaunchTestTarget = { viewModel.launchControlledTarget(context) },
                        onStartService = { viewModel.startBackgroundService(context) },
                        onStopService = { viewModel.stopBackgroundService(context) },
                        onShowModuleGuide = viewModel::setShowModuleGuideDialog
                    )
                }
            }
        }
    }
}

