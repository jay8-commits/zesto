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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ElegantDarkBackground
import com.example.ui.theme.ElegantDarkOnPrimary
import com.example.ui.theme.ElegantDarkOutlineVariant
import com.example.ui.theme.ElegantDarkPrimary
import com.example.ui.theme.ElegantDarkSurfaceVariant
import com.example.ui.theme.ElegantDarkTextPrimary
import com.example.ui.theme.ElegantDarkTextSecondary
import com.example.zesto.diagnostics.DiagnosticsLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(
    uiState: ZestoUiState,
    onExportLog: () -> Unit,
    onDismissExportDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header & Export Action matching Elegant Dark section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SYSTEM TELEMETRY",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = ElegantDarkTextSecondary,
                letterSpacing = 1.2.sp,
                fontFamily = FontFamily.Monospace
            )

            Button(
                onClick = onExportLog,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFEADDFF),
                    contentColor = Color(0xFF21005D)
                ),
                modifier = Modifier.testTag("export_log_button")
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp).padding(end = 4.dp))
                Text("EXPORT LOG", fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }

        // Active Fault Isolation Banner (Identifies responsible layer on failure)
        uiState.diagnosticsSnapshot.faultSubsystem?.let { fault ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D).copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.padding(end = 10.dp)
                    )
                    Column {
                        Text(
                            text = "FAULT DETECTED: ${fault.name}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFECACA),
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = uiState.diagnosticsSnapshot.lastErrorMessage ?: "Subsystem reported failure state.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFCA5A5)
                        )
                    }
                }
            }
        }

        // Section 1: TRANSPORT
        DiagnosticsSectionCard(title = "TRANSPORT LAYER") {
            DiagnosticsRow(label = "Status", value = uiState.diagnosticsSnapshot.transportStatus)
            DiagnosticsRow(label = "Type", value = uiState.diagnosticsSnapshot.transportType)
            DiagnosticsRow(label = "URL", value = uiState.diagnosticsSnapshot.rtspUrl.ifEmpty { "None" })
            DiagnosticsRow(label = "Reconnect Count", value = "${uiState.diagnosticsSnapshot.reconnectCount}")
            DiagnosticsRow(label = "Packets Ingested", value = "${uiState.diagnosticsSnapshot.streamStats.packetsReceived}")
            DiagnosticsRow(label = "Bitrate", value = "${String.format(Locale.US, "%.1f", uiState.diagnosticsSnapshot.streamStats.estimatedBitrateKbps)} kbps")
        }

        // Section 2: DECODER
        DiagnosticsSectionCard(title = "DECODER LAYER") {
            DiagnosticsRow(label = "Status", value = uiState.diagnosticsSnapshot.decoderStatus)
            DiagnosticsRow(label = "Resolution", value = uiState.diagnosticsSnapshot.decoderResolution)
            DiagnosticsRow(label = "Output FPS", value = String.format(Locale.US, "%.1f", uiState.diagnosticsSnapshot.decoderFps))
            DiagnosticsRow(label = "Decoded Frames", value = "${uiState.diagnosticsSnapshot.decodedFrames}")
            DiagnosticsRow(label = "Decoder Dropped", value = "${uiState.diagnosticsSnapshot.decoderDroppedFrames}")
            DiagnosticsRow(label = "Decode Errors", value = "${uiState.diagnosticsSnapshot.decodeErrors}")
        }

        // Section 3: FRAME PIPELINE
        DiagnosticsSectionCard(title = "FRAME PROVIDER & PIPELINE") {
            DiagnosticsRow(label = "Status", value = uiState.diagnosticsSnapshot.pipelineStatus)
            DiagnosticsRow(label = "Delivered Frames", value = "${uiState.diagnosticsSnapshot.deliveredFrames}")
            DiagnosticsRow(label = "Pipeline Dropped", value = "${uiState.diagnosticsSnapshot.pipelineDroppedFrames}")
            DiagnosticsRow(label = "Queue Latency", value = "${uiState.diagnosticsSnapshot.pipelineLatencyMs} ms")
            DiagnosticsRow(label = "Active Consumers", value = "${uiState.diagnosticsSnapshot.activeConsumers}")
        }

        // Section 4: CAMERA HARDWARE & API
        DiagnosticsSectionCard(title = "CAMERA API INTEGRATION") {
            DiagnosticsRow(label = "Detected API", value = uiState.diagnosticsSnapshot.detectedCameraApi.displayName)
            DiagnosticsRow(label = "Hardware Level", value = uiState.diagnosticsSnapshot.cameraHardwareLevel)
        }

        // Section 5: VIRTUALIZATION
        DiagnosticsSectionCard(title = "VIRTUALIZATION LAYER") {
            DiagnosticsRow(label = "Active Backend", value = uiState.diagnosticsSnapshot.activeBackend)
            DiagnosticsRow(label = "Status", value = uiState.diagnosticsSnapshot.virtualizationStatus.name)
        }

        // Section 6: TARGET APP
        DiagnosticsSectionCard(title = "TARGET APPLICATION") {
            DiagnosticsRow(label = "Package Name", value = uiState.diagnosticsSnapshot.targetPackage)
            DiagnosticsRow(label = "Compat Status", value = uiState.diagnosticsSnapshot.targetStatus)
        }

        // Event Logs Section
        Text(
            text = "EVENT LOG (${uiState.eventLogs.size})",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = ElegantDarkTextSecondary,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .border(1.dp, ElegantDarkOutlineVariant, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = ElegantDarkBackground)
        ) {
            val logScroll = rememberScrollState()
            val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(logScroll)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (uiState.eventLogs.isEmpty()) {
                    Text(
                        text = "No diagnostic events logged yet.",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    uiState.eventLogs.reversed().forEach { event ->
                        val color = when (event.level) {
                            DiagnosticsLevel.ERROR -> Color(0xFFF87171)
                            DiagnosticsLevel.WARNING -> Color(0xFFFBBF24)
                            DiagnosticsLevel.INFO -> Color(0xFF60A5FA)
                            DiagnosticsLevel.DEBUG -> Color(0xFF94A3B8)
                        }
                        Text(
                            text = "[${timeFormatter.format(Date(event.timestampMs))}] [${event.subsystem.name}] ${event.message}",
                            color = color,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }

    // Export Log Dialog
    uiState.exportedLogText?.let { logText ->
        AlertDialog(
            onDismissRequest = onDismissExportDialog,
            containerColor = ElegantDarkSurfaceVariant,
            titleContentColor = ElegantDarkTextPrimary,
            textContentColor = ElegantDarkTextSecondary,
            title = { Text("Exported Diagnostics Report", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            text = {
                val dialogScroll = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .background(ElegantDarkBackground, RoundedCornerShape(12.dp))
                        .border(1.dp, ElegantDarkOutlineVariant, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                        .verticalScroll(dialogScroll)
                ) {
                    Text(
                        text = logText,
                        color = Color(0xFFE2E8F0),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(logText))
                        onDismissExportDialog()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElegantDarkPrimary,
                        contentColor = ElegantDarkOnPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Copy to Clipboard", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissExportDialog) {
                    Text("Close", color = ElegantDarkTextSecondary)
                }
            }
        )
    }
}

@Composable
fun DiagnosticsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ElegantDarkOutlineVariant, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = ElegantDarkSurfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = ElegantDarkPrimary,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = ElegantDarkOutlineVariant)
            content()
        }
    }
}

@Composable
fun DiagnosticsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = ElegantDarkTextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = ElegantDarkTextPrimary,
            fontFamily = FontFamily.Monospace
        )
    }
}
