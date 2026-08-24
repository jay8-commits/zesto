package com.example.zesto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.example.zesto.stream.TransportProtocol

@Composable
fun StreamConfigScreen(
    uiState: ZestoUiState,
    onUrlChange: (String) -> Unit,
    onProtocolChange: (TransportProtocol) -> Unit,
    onResolutionChange: (Int, Int) -> Unit,
    onFpsChange: (Int) -> Unit,
    onTestConnection: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
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
        // Transport Configuration Card (matching Elegant Dark style)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, ElegantDarkOutlineVariant, RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = ElegantDarkSurfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "TRANSPORT CONFIGURATION",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = ElegantDarkTextSecondary,
                    letterSpacing = 1.2.sp,
                    fontFamily = FontFamily.Monospace
                )

                // Input + Test Button Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = uiState.streamConfig.url,
                        onValueChange = onUrlChange,
                        label = null,
                        placeholder = { Text("rtsp://192.168.1.104:8554/live", color = Color(0xFF64748B), fontSize = 13.sp) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("rtsp_url_input"),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = ElegantDarkBackground,
                            unfocusedContainerColor = ElegantDarkBackground,
                            disabledContainerColor = ElegantDarkBackground,
                            focusedBorderColor = ElegantDarkPrimary,
                            unfocusedBorderColor = ElegantDarkOutlineVariant,
                            focusedTextColor = ElegantDarkTextPrimary,
                            unfocusedTextColor = ElegantDarkTextPrimary
                        ),
                        enabled = !uiState.isConnected && !uiState.isConnecting
                    )

                    Button(
                        onClick = onTestConnection,
                        enabled = !uiState.isTestingConnection && uiState.streamConfig.url.isNotBlank(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElegantDarkPrimary,
                            contentColor = ElegantDarkOnPrimary,
                            disabledContainerColor = ElegantDarkOutlineVariant,
                            disabledContentColor = Color.Gray
                        ),
                        modifier = Modifier
                            .height(54.dp)
                            .testTag("test_connection_button")
                    ) {
                        if (uiState.isTestingConnection) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = ElegantDarkOnPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("TEST", fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        }
                    }
                }

                // Connect / Disconnect Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onConnect,
                        enabled = !uiState.isConnected && !uiState.isConnecting,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.isConnected) ElegantDarkOutlineVariant else ElegantDarkPrimary,
                            contentColor = if (uiState.isConnected) Color.White else ElegantDarkOnPrimary,
                            disabledContainerColor = ElegantDarkOutlineVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("connect_button")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 4.dp))
                        Text("CONNECT", fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = onDisconnect,
                        enabled = uiState.isConnected || uiState.isConnecting,
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ElegantDarkOutlineVariant),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFEF4444)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("disconnect_button")
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 4.dp))
                        Text("DISCONNECT", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Connection Test Result Banner
        uiState.connectionTestResult?.let { result ->
            val isSuccess = result.startsWith("SUCCESS")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (isSuccess) ElegantDarkTertiary.copy(alpha = 0.5f) else Color(0xFFEF4444).copy(alpha = 0.5f),
                        RoundedCornerShape(16.dp)
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSuccess) Color(0xFF064E3B).copy(alpha = 0.4f) else Color(0xFF7F1D1D).copy(alpha = 0.4f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isSuccess) ElegantDarkTertiary else Color(0xFFEF4444),
                        modifier = Modifier.padding(end = 10.dp)
                    )
                    Text(
                        text = result,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSuccess) Color(0xFFA7F3D0) else Color(0xFFFECACA),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Protocol Selection
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, ElegantDarkOutlineVariant, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = ElegantDarkSurfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "TRANSPORT PROTOCOL",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = ElegantDarkTextSecondary,
                    letterSpacing = 1.sp,
                    fontFamily = FontFamily.Monospace
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = uiState.streamConfig.protocol == TransportProtocol.RTSP_TCP,
                        onClick = { onProtocolChange(TransportProtocol.RTSP_TCP) },
                        label = { Text("RTSP / TCP") },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElegantDarkPrimary,
                            selectedLabelColor = ElegantDarkOnPrimary,
                            containerColor = ElegantDarkBackground,
                            labelColor = ElegantDarkTextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = if (uiState.streamConfig.protocol == TransportProtocol.RTSP_TCP) ElegantDarkPrimary else ElegantDarkOutlineVariant,
                            selectedBorderColor = ElegantDarkPrimary,
                            enabled = true,
                            selected = uiState.streamConfig.protocol == TransportProtocol.RTSP_TCP
                        ),
                        enabled = !uiState.isConnected
                    )
                    FilterChip(
                        selected = uiState.streamConfig.protocol == TransportProtocol.RTSP_UDP,
                        onClick = { onProtocolChange(TransportProtocol.RTSP_UDP) },
                        label = { Text("RTSP / UDP") },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElegantDarkPrimary,
                            selectedLabelColor = ElegantDarkOnPrimary,
                            containerColor = ElegantDarkBackground,
                            labelColor = ElegantDarkTextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = if (uiState.streamConfig.protocol == TransportProtocol.RTSP_UDP) ElegantDarkPrimary else ElegantDarkOutlineVariant,
                            selectedBorderColor = ElegantDarkPrimary,
                            enabled = true,
                            selected = uiState.streamConfig.protocol == TransportProtocol.RTSP_UDP
                        ),
                        enabled = !uiState.isConnected
                    )
                }
            }
        }

        // Resolution & FPS Settings
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, ElegantDarkOutlineVariant, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = ElegantDarkSurfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "TARGET RESOLUTION & FRAMERATE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = ElegantDarkTextSecondary,
                    letterSpacing = 1.sp,
                    fontFamily = FontFamily.Monospace
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = uiState.streamConfig.targetWidth == 1280 && uiState.streamConfig.targetHeight == 720,
                        onClick = { onResolutionChange(1280, 720) },
                        label = { Text("720p") },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElegantDarkPrimary,
                            selectedLabelColor = ElegantDarkOnPrimary,
                            containerColor = ElegantDarkBackground,
                            labelColor = ElegantDarkTextSecondary
                        ),
                        enabled = !uiState.isConnected
                    )
                    FilterChip(
                        selected = uiState.streamConfig.targetWidth == 1920 && uiState.streamConfig.targetHeight == 1080,
                        onClick = { onResolutionChange(1920, 1080) },
                        label = { Text("1080p") },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElegantDarkPrimary,
                            selectedLabelColor = ElegantDarkOnPrimary,
                            containerColor = ElegantDarkBackground,
                            labelColor = ElegantDarkTextSecondary
                        ),
                        enabled = !uiState.isConnected
                    )
                    FilterChip(
                        selected = uiState.streamConfig.targetWidth == 640 && uiState.streamConfig.targetHeight == 480,
                        onClick = { onResolutionChange(640, 480) },
                        label = { Text("480p") },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElegantDarkPrimary,
                            selectedLabelColor = ElegantDarkOnPrimary,
                            containerColor = ElegantDarkBackground,
                            labelColor = ElegantDarkTextSecondary
                        ),
                        enabled = !uiState.isConnected
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = uiState.streamConfig.targetFps == 30,
                        onClick = { onFpsChange(30) },
                        label = { Text("30 FPS") },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElegantDarkPrimary,
                            selectedLabelColor = ElegantDarkOnPrimary,
                            containerColor = ElegantDarkBackground,
                            labelColor = ElegantDarkTextSecondary
                        ),
                        enabled = !uiState.isConnected
                    )
                    FilterChip(
                        selected = uiState.streamConfig.targetFps == 60,
                        onClick = { onFpsChange(60) },
                        label = { Text("60 FPS") },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElegantDarkPrimary,
                            selectedLabelColor = ElegantDarkOnPrimary,
                            containerColor = ElegantDarkBackground,
                            labelColor = ElegantDarkTextSecondary
                        ),
                        enabled = !uiState.isConnected
                    )
                }
            }
        }
    }
}
