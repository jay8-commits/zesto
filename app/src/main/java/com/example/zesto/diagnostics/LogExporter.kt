package com.example.zesto.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Formats system diagnostics snapshots and logs into structured reports for export and debugging.
 */
object LogExporter {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun exportAsMarkdown(snapshot: DiagnosticsSnapshot, logs: List<DiagnosticsEvent>): String {
        val dateStr = dateFormat.format(Date(snapshot.timestamp))
        val sb = StringBuilder()

        sb.appendLine("==================================================")
        sb.appendLine("ZESTO DIAGNOSTICS LOG EXPORT")
        sb.appendLine("Generated At: $dateStr")
        sb.appendLine("==================================================")
        sb.appendLine()

        sb.appendLine("--- TRANSPORT LAYER ---")
        sb.appendLine("Status: ${snapshot.transportStatus}")
        sb.appendLine("Type: ${snapshot.transportType}")
        sb.appendLine("URL: ${snapshot.rtspUrl}")
        sb.appendLine("Reconnects: ${snapshot.reconnectCount}")
        sb.appendLine("Packets Received: ${snapshot.streamStats.packetsReceived}")
        sb.appendLine("Bitrate: ${String.format(Locale.US, "%.1f", snapshot.streamStats.estimatedBitrateKbps)} kbps")
        sb.appendLine()

        sb.appendLine("--- VIDEO DECODER LAYER ---")
        sb.appendLine("Status: ${snapshot.decoderStatus}")
        sb.appendLine("Resolution: ${snapshot.decoderResolution}")
        sb.appendLine("FPS: ${String.format(Locale.US, "%.1f", snapshot.decoderFps)}")
        sb.appendLine("Decoded Frames: ${snapshot.decodedFrames}")
        sb.appendLine("Dropped Frames: ${snapshot.decoderDroppedFrames}")
        sb.appendLine("Decode Errors: ${snapshot.decodeErrors}")
        sb.appendLine("Decode Latency: ${snapshot.decoderStats.averageDecodeLatencyMs} ms")
        sb.appendLine()

        sb.appendLine("--- FRAME PIPELINE LAYER ---")
        sb.appendLine("Status: ${snapshot.pipelineStatus}")
        sb.appendLine("Delivered: ${snapshot.deliveredFrames}")
        sb.appendLine("Dropped: ${snapshot.pipelineDroppedFrames}")
        sb.appendLine("Latency: ${snapshot.pipelineLatencyMs} ms")
        sb.appendLine("Active Consumers: ${snapshot.activeConsumers}")
        sb.appendLine()

        sb.appendLine("--- CAMERA API LAYER ---")
        sb.appendLine("Detected API: ${snapshot.detectedCameraApi.displayName}")
        sb.appendLine("Hardware Level: ${snapshot.cameraHardwareLevel}")
        sb.appendLine()

        sb.appendLine("--- VIRTUALIZATION BACKEND ---")
        sb.appendLine("Backend: ${snapshot.activeBackend}")
        sb.appendLine("Status: ${snapshot.virtualizationStatus.name} (${snapshot.virtualizationStatus.description})")
        sb.appendLine()

        sb.appendLine("--- TARGET APPLICATION ---")
        sb.appendLine("Target Package: ${snapshot.targetPackage}")
        sb.appendLine("Target Status: ${snapshot.targetStatus}")
        sb.appendLine()

        sb.appendLine("--- PIPELINE BOUNDARY MILESTONES ---")
        BoundaryDiagnosticStage.entries.forEach { stage ->
            val achieved = snapshot.activeBoundaries.contains(stage)
            val mark = if (achieved) "[X]" else "[ ]"
            sb.appendLine("$mark ${stage.code}: ${stage.description}")
        }
        sb.appendLine()

        if (snapshot.faultSubsystem != null) {
            sb.appendLine("--- ACTIVE FAULT IDENTIFICATION ---")
            sb.appendLine("Faulting Subsystem: ${snapshot.faultSubsystem.name}")
            sb.appendLine("Error: ${snapshot.lastErrorMessage ?: "Unknown"}")
            sb.appendLine()
        }

        sb.appendLine("--- EVENT LOG (${logs.size} entries) ---")
        if (logs.isEmpty()) {
            sb.appendLine("(No logged events)")
        } else {
            logs.forEach { log ->
                val time = dateFormat.format(Date(log.timestampMs))
                sb.appendLine("[$time] [${log.level.name}] [${log.subsystem.name}] ${log.message}")
                if (!log.errorDetails.isNullOrEmpty()) {
                    sb.appendLine("    Details: ${log.errorDetails}")
                }
            }
        }
        sb.appendLine("==================================================")
        return sb.toString()
    }
}
