package com.example.zesto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ElegantDarkBackground
import com.example.ui.theme.ElegantDarkOnPrimary
import com.example.ui.theme.ElegantDarkOutlineVariant
import com.example.ui.theme.ElegantDarkPrimary
import com.example.ui.theme.ElegantDarkSurfaceVariant
import com.example.ui.theme.ElegantDarkTertiary
import com.example.ui.theme.ElegantDarkTextPrimary
import com.example.ui.theme.ElegantDarkTextSecondary
import com.example.zesto.camera.CameraVirtualizationStatus
import com.example.zesto.target.TargetProfile

@Composable
fun TargetCompatibilityScreen(
    uiState: ZestoUiState,
    onSelectProfile: (TargetProfile) -> Unit,
    onSearchChange: (String) -> Unit = {},
    onLaunchTestTarget: () -> Unit = {},
    onStartService: () -> Unit = {},
    onStopService: () -> Unit = {},
    onShowModuleGuide: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Truth-in-Engineering Notice Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f), RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF261D10))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(18.dp))
                    Text(
                        text = "STAGE 3 TRUTH-IN-ENGINEERING NOTICE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF59E0B),
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    text = "• IMPLEMENTED: Code architecture, IPC Provider, Camera2/CameraX hooks, and profiles are fully compiled.\n• VERIFICATION: Physical target-app injection requires on-device LSPatch / LSPosed runtime execution. All profiles default to NOT_TESTED until physically validated.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFEF3C7),
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        }

        // Quick Action / Service Control Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, ElegantDarkOutlineVariant, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = ElegantDarkSurfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "BACKGROUND SERVICE & HARNESS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = ElegantDarkPrimary,
                            letterSpacing = 1.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = if (uiState.isServiceRunning) "Foreground Service ACTIVE (Streaming in background)" else "Foreground Service IDLE",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uiState.isServiceRunning) ElegantDarkTertiary else ElegantDarkTextSecondary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (uiState.isServiceRunning) ElegantDarkTertiary else Color(0xFF64748B))
                    )
                }

                HorizontalDivider(color = ElegantDarkOutlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!uiState.isServiceRunning) {
                        Button(
                            onClick = onStartService,
                            colors = ButtonDefaults.buttonColors(containerColor = ElegantDarkPrimary, contentColor = ElegantDarkOnPrimary),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).testTag("btn_start_service")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Start Service", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = onStopService,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444), contentColor = Color.White),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).testTag("btn_stop_service")
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Stop Service", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Button(
                        onClick = onLaunchTestTarget,
                        colors = ButtonDefaults.buttonColors(containerColor = ElegantDarkTertiary, contentColor = Color.Black),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).testTag("btn_launch_target")
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Test Harness", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedButton(
                    onClick = { onShowModuleGuide(true) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Extension, contentDescription = null, tint = ElegantDarkPrimary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Module Setup: LSPatch (Non-Root) & LSPosed (Root)", color = ElegantDarkPrimary, fontSize = 12.sp)
                }
            }
        }

        // Device Camera Hardware Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, ElegantDarkOutlineVariant, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = ElegantDarkSurfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = ElegantDarkPrimary,
                        modifier = Modifier.size(20.dp).padding(end = 8.dp)
                    )
                    Text(
                        text = "DEVICE CAMERA SUBSYSTEM",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ElegantDarkPrimary,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = ElegantDarkOutlineVariant)

                DiagnosticsRow(label = "Primary API Architecture", value = uiState.cameraCapabilities.apiType.displayName)
                DiagnosticsRow(label = "Camera Hardware Level", value = uiState.cameraCapabilities.hardwareLevel)
                DiagnosticsRow(label = "Physical Sensors Detected", value = "${uiState.cameraCapabilities.cameraCount}")
                DiagnosticsRow(label = "SurfaceTexture Direct Support", value = if (uiState.cameraCapabilities.supportsSurfaceTexture) "YES" else "NO")
            }
        }

        // Search and Filter Bar
        OutlinedTextField(
            value = uiState.profileSearchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth().testTag("input_profile_search"),
            placeholder = { Text("Search targets by app, package, or status...", color = ElegantDarkTextSecondary, fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = ElegantDarkPrimary) },
            shape = RoundedCornerShape(14.dp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ElegantDarkPrimary,
                unfocusedBorderColor = ElegantDarkOutlineVariant,
                focusedContainerColor = ElegantDarkSurfaceVariant,
                unfocusedContainerColor = ElegantDarkSurfaceVariant,
                focusedTextColor = ElegantDarkTextPrimary,
                unfocusedTextColor = ElegantDarkTextPrimary
            )
        )

        // Target Profiles List Header
        Text(
            text = "TARGET PROFILES (${uiState.targetProfiles.size})",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = ElegantDarkTextSecondary,
            letterSpacing = 1.sp,
            fontFamily = FontFamily.Monospace
        )

        uiState.targetProfiles.forEach { profile ->
            val isSelected = uiState.selectedTargetProfile?.id == profile.id
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectProfile(profile) }
                    .border(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) ElegantDarkPrimary else ElegantDarkOutlineVariant,
                        shape = RoundedCornerShape(20.dp)
                    ),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) Color(0xFF353147) else ElegantDarkSurfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = profile.appName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = ElegantDarkTextPrimary
                            )
                            Text(
                                text = profile.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = ElegantDarkTextSecondary
                            )
                        }

                        RadioButton(
                            selected = isSelected,
                            onClick = { onSelectProfile(profile) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = ElegantDarkPrimary,
                                unselectedColor = ElegantDarkOutlineVariant
                            )
                        )
                    }

                    // Status Badge
                    val statusColor = when (profile.testStatus) {
                        CameraVirtualizationStatus.ACTIVE -> ElegantDarkTertiary
                        CameraVirtualizationStatus.SUPPORTED -> ElegantDarkTertiary
                        CameraVirtualizationStatus.TESTING -> Color(0xFFFBBF24)
                        CameraVirtualizationStatus.REQUIRES_INSTRUMENTATION -> Color(0xFF38BDF8)
                        CameraVirtualizationStatus.REQUIRES_ROOT -> Color(0xFFF97316)
                        CameraVirtualizationStatus.UNSUPPORTED -> Color(0xFFEF4444)
                        CameraVirtualizationStatus.FAILED -> Color(0xFFEF4444)
                        CameraVirtualizationStatus.NOT_TESTED -> Color(0xFF94A3B8)
                        else -> Color(0xFF94A3B8)
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(statusColor.copy(alpha = 0.15f))
                            .border(1.dp, statusColor, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = profile.testStatus.name,
                            color = statusColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = ElegantDarkOutlineVariant)

                    DiagnosticsRow(label = "Target Camera API", value = profile.cameraApi.name)
                    DiagnosticsRow(label = "Backend Adapter", value = profile.supportedBackend)
                    DiagnosticsRow(label = "Integration Mechanism", value = profile.integrationMechanism)
                    DiagnosticsRow(label = "Root Required", value = if (profile.requiresRoot) "YES (LSPosed)" else "NO (LSPatch Non-Root)")
                    DiagnosticsRow(label = "Instrumentation Req.", value = if (profile.requiresInstrumentation) "YES" else "NO")

                    Text(
                        text = "Limitations: ${profile.knownLimitations}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ElegantDarkTextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = "Diagnostics: ${profile.diagnosticInfo}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ElegantDarkPrimary.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }

    // Module Guide Dialog
    if (uiState.showModuleGuideDialog) {
        AlertDialog(
            onDismissRequest = { onShowModuleGuide(false) },
            containerColor = ElegantDarkSurfaceVariant,
            title = {
                Text(
                    text = "Zesto Module Instrumentation Architecture",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ElegantDarkPrimary
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "1. Non-Root (LSPatch / NPatch Portable Mode):",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8),
                        fontSize = 13.sp
                    )
                    Text(
                        text = "• Download LSPatch / NPatch.\n• Select target app (e.g. Open Camera net.sourceforge.opencamera or beauty camera photo.camera.beauty.hd.camera).\n• Choose 'Portable Mode' or 'Local Mode'.\n• Under Embedded Modules, select Zesto APK.\n• IMPORTANT: Ensure Application Class preservation is enabled (do not override android:name with an unbundled stub).\n• Install patched target APK. The app launches its legitimate Application lifecycle, hooks Camera2, and receives live frames via ZestoFrameContentProvider without root.",
                        color = ElegantDarkTextPrimary,
                        fontSize = 12.sp
                    )

                    HorizontalDivider(color = ElegantDarkOutlineVariant)

                    Text(
                        text = "2. Rooted (LSPosed / Magisk / KernelSU):",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF97316),
                        fontSize = 13.sp
                    )
                    Text(
                        text = "• Install Zesto APK.\n• Open LSPosed Manager -> Modules -> Enable 'Zesto Camera Virtualization Hook'.\n• Check target apps in the module scope.\n• Force-stop target app and launch. Real-time OBS video feed will substitute the camera hardware sensor.",
                        color = ElegantDarkTextPrimary,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onShowModuleGuide(false) }) {
                    Text("Got It", color = ElegantDarkPrimary, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

