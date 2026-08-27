package com.example.zesto.ui

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import com.example.ui.theme.ElegantDarkBackground
import com.example.ui.theme.ElegantDarkOnPrimary
import com.example.ui.theme.ElegantDarkOutlineVariant
import com.example.ui.theme.ElegantDarkPrimary
import com.example.ui.theme.ElegantDarkSurfaceVariant
import com.example.ui.theme.ElegantDarkTertiary
import com.example.ui.theme.ElegantDarkTextPrimary
import com.example.ui.theme.ElegantDarkTextSecondary
import com.example.zesto.frame.ZestoFrameBridge
import java.util.Locale

@OptIn(UnstableApi::class)
@Composable
fun PreviewScreen(
    uiState: ZestoUiState,
    onStartDecoder: () -> Unit,
    onStopDecoder: () -> Unit,
    onFrameDecoded: ((Bitmap, Int, Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val isLiveVideoActive = (uiState.isDecoding || uiState.isConnected) && uiState.player != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Stream Preview Viewport - Authoritative Decoded Video Canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black)
                .border(1.dp, ElegantDarkOutlineVariant, RoundedCornerShape(24.dp))
                .testTag("stream_preview_box"),
            contentAlignment = Alignment.Center
        ) {
            if (isLiveVideoActive) {
                // Real Live Video Rendering via Hardware-Accelerated TextureView
                AndroidView(
                    factory = { context ->
                        TextureView(context).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                                    uiState.player?.setVideoTextureView(this@apply)
                                    Log.i("PreviewScreen", "[TEXTURE_SURFACE_AVAILABLE] TextureView attached to ExoPlayer (${width}x${height})")
                                }

                                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

                                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                    uiState.player?.clearVideoTextureView(this@apply)
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                                    val bmp = bitmap
                                    if (bmp != null && !bmp.isRecycled) {
                                        onFrameDecoded?.invoke(bmp, bmp.width, bmp.height)
                                        val id = ZestoFrameBridge.latestFrame.value.frameId
                                        if (id == 1L || id % 60L == 0L) {
                                            Log.i("PreviewScreen", "[ZESTO_PREVIEW_RENDERED] id=$id dimensions=${bmp.width}x${bmp.height}")
                                        }
                                    }
                                }
                            }
                        }
                    },
                    update = { textureView ->
                        if (uiState.player != null) {
                            uiState.player?.setVideoTextureView(textureView)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Standby Placeholder Visual
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .border(
                                width = 1.5.dp,
                                color = if (uiState.isDecoding) ElegantDarkTertiary else ElegantDarkTextSecondary.copy(alpha = 0.5f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (uiState.isDecoding) ElegantDarkTertiary else ElegantDarkTextSecondary)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = if (uiState.isConnecting) "CONNECTING TO RTSP..." else "STANDBY / 9:16 STREAM READY",
                        color = if (uiState.isConnecting) ElegantDarkPrimary else ElegantDarkTextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                }
            }

            // Top-Left Status HUD Badge (Live Signal & Status)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                contentAlignment = Alignment.TopStart
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .border(1.dp, if (uiState.isConnected) ElegantDarkTertiary.copy(alpha = 0.5f) else ElegantDarkOutlineVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "STREAM: ${uiState.diagnosticsSnapshot.transportStatus}",
                        color = if (uiState.isConnected) ElegantDarkTertiary else if (uiState.isConnecting) Color(0xFFFBBF24) else ElegantDarkTextSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Top-Right Metrics HUD (Decoded, Dropped, Latency)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .border(1.dp, ElegantDarkOutlineVariant.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "DEC:${uiState.diagnosticsSnapshot.decodedFrames} | DROP:${uiState.diagnosticsSnapshot.decoderDroppedFrames} | ${uiState.diagnosticsSnapshot.pipelineLatencyMs}ms",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Bottom-Right Codec / Hardware Badge
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .border(1.dp, ElegantDarkOutlineVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "H.264 | MEDIACODEC HW",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Live Controls (START / STOP)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onStartDecoder,
                enabled = !uiState.isDecoding,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("start_preview_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElegantDarkPrimary,
                    contentColor = ElegantDarkOnPrimary,
                    disabledContainerColor = ElegantDarkOutlineVariant
                )
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 4.dp))
                Text("START", fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onStopDecoder,
                enabled = uiState.isDecoding,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("stop_preview_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFEF4444),
                    contentColor = Color.White,
                    disabledContainerColor = ElegantDarkOutlineVariant.copy(alpha = 0.4f)
                )
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 4.dp))
                Text("STOP", fontWeight = FontWeight.Bold)
            }
        }

        // 2x2 Metric Grid matching Elegant Dark Layout
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Resolution Metric Box
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, ElegantDarkOutlineVariant, RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = ElegantDarkSurfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "RESOLUTION",
                            color = ElegantDarkTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "${uiState.streamConfig.targetWidth} × ${uiState.streamConfig.targetHeight}",
                            color = ElegantDarkPrimary,
                            fontSize = 17.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Framerate Metric Box
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, ElegantDarkOutlineVariant, RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = ElegantDarkSurfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "FRAMERATE",
                            color = ElegantDarkTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "${String.format(Locale.US, "%.1f", uiState.diagnosticsSnapshot.decoderFps)} FPS",
                            color = ElegantDarkPrimary,
                            fontSize = 17.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Detected API Metric Box
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, ElegantDarkOutlineVariant, RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = ElegantDarkSurfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "DETECTED API",
                            color = ElegantDarkTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = uiState.cameraCapabilities.apiType.name,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Latency Metric Box
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, ElegantDarkOutlineVariant, RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = ElegantDarkSurfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "LATENCY",
                            color = ElegantDarkTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "${uiState.diagnosticsSnapshot.pipelineLatencyMs}ms",
                            color = ElegantDarkTertiary,
                            fontSize = 17.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

