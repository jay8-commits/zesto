package com.example.zesto.testtarget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.ui.theme.ElegantDarkBackground
import com.example.ui.theme.ElegantDarkOnPrimary
import com.example.ui.theme.ElegantDarkOutlineVariant
import com.example.ui.theme.ElegantDarkPrimary
import com.example.ui.theme.ElegantDarkSurfaceVariant
import com.example.ui.theme.ElegantDarkTertiary
import com.example.ui.theme.ElegantDarkTextPrimary
import com.example.ui.theme.ElegantDarkTextSecondary
import com.example.ui.theme.ZestoTheme
import com.example.zesto.frame.FrameHealthState
import com.example.zesto.frame.ZestoFrameBridge
import com.example.zesto.hook.Camera2Hook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Controlled Camera Test Target Activity.
 * A self-contained test application harness utilizing real Android Camera2 hardware pipelines,
 * allowing live side-by-side verification of physical sensor frames vs Zesto virtual injected frames.
 */
class ControlledCameraTestActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ControlledCameraTest"
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Log.i(TAG, "[TARGET_PROCESS_ATTACHED] Test target process initialized: ${packageName}")

        // Attach Camera2 hook to classloader
        Camera2Hook.attachHook(classLoader)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        }

        startBackgroundThread()

        setContent {
            ZestoTheme {
                ControlledCameraTestScreen(
                    onBack = { finish() },
                    startCamera = { textureView -> openCamera(textureView) },
                    stopCamera = { closeCamera() }
                )
            }
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (_: InterruptedException) {}
    }

    private fun openCamera(textureView: TextureView) {
        val manager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        try {
            val cameraId = manager.cameraIdList.firstOrNull() ?: return
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return
            }

            // Signal Camera2 device opening boundary
            Camera2Hook.onCameraDeviceOpening(cameraId)

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createPreviewSession(textureView)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)
        } catch (_: Exception) {}
    }

    private fun createPreviewSession(textureView: TextureView) {
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(1280, 720)
        val surface = Surface(texture)

        // Signal session configured to Camera2Hook
        Camera2Hook.onSessionConfigured(listOf(surface))

        try {
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder?.addTarget(surface)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val outputConfig = android.hardware.camera2.params.OutputConfiguration(surface)
                val executor = java.util.concurrent.Executor { command -> backgroundHandler?.post(command) }
                val sessionConfig = android.hardware.camera2.params.SessionConfiguration(
                    android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                    listOf(outputConfig),
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (cameraDevice == null) return
                            captureSession = session
                            builder?.let {
                                session.setRepeatingRequest(it.build(), null, backgroundHandler)
                            }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {}
                    }
                )
                cameraDevice?.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                cameraDevice?.createCaptureSession(
                    listOf(surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (cameraDevice == null) return
                            captureSession = session
                            builder?.let {
                                session.setRepeatingRequest(it.build(), null, backgroundHandler)
                            }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {}
                    },
                    backgroundHandler
                )
            }
        } catch (_: Exception) {}
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        Camera2Hook.stopFramePump()
    }

    override fun onDestroy() {
        super.onDestroy()
        closeCamera()
        stopBackgroundThread()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlledCameraTestScreen(
    onBack: () -> Unit,
    startCamera: (TextureView) -> Unit,
    stopCamera: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isVirtualInjectionActive by remember { mutableStateOf(false) }
    var frameCount by remember { mutableLongStateOf(0L) }
    var droppedFrames by remember { mutableLongStateOf(0L) }
    var measuredFps by remember { mutableDoubleStateOf(0.0) }
    var activeResolution by remember { mutableStateOf("1280 x 720") }
    var healthState by remember { mutableStateOf(FrameHealthState.NO_FRAME) }
    var msSinceLastFrame by remember { mutableLongStateOf(-1L) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }

    var physicalFramesRendered by remember { mutableLongStateOf(0L) }
    var lastPhysicalFpsTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var physicalFpsCounter by remember { mutableLongStateOf(0L) }

    // Virtual frame rendering loop
    LaunchedEffect(isVirtualInjectionActive, textureViewRef) {
        val tv = textureViewRef
        if (isVirtualInjectionActive && tv != null) {
            val paint = Paint().apply { isFilterBitmap = true }
            val textPaint = Paint().apply {
                color = AndroidColor.WHITE
                textSize = 34f
                isAntiAlias = true
            }

            var lastVirtualFpsTimestamp = System.currentTimeMillis()
            var virtualFramesInSecond = 0L

            while (isActive) {
                val frame = ZestoFrameBridge.consumeLatestFrame()
                healthState = ZestoFrameBridge.getFrameHealthState()
                msSinceLastFrame = ZestoFrameBridge.getMillisecondsSinceLastFrame()
                frameCount = ZestoFrameBridge.totalFramesDelivered
                droppedFrames = ZestoFrameBridge.totalFramesDropped

                val now = System.currentTimeMillis()
                if (now - lastVirtualFpsTimestamp >= 1000L) {
                    val elapsed = now - lastVirtualFpsTimestamp
                    measuredFps = if (elapsed > 0) (virtualFramesInSecond * 1000.0) / elapsed else 0.0
                    virtualFramesInSecond = 0L
                    lastVirtualFpsTimestamp = now
                }

                if (tv.isAvailable) {
                    val canvas: Canvas? = tv.lockCanvas()
                    if (canvas != null) {
                        try {
                            if (frame.bitmap != null) {
                                val srcRect = Rect(0, 0, frame.bitmap.width, frame.bitmap.height)
                                val dstRect = Rect(0, 0, canvas.width, canvas.height)
                                canvas.drawBitmap(frame.bitmap, srcRect, dstRect, paint)
                                virtualFramesInSecond++
                                activeResolution = "${frame.width} x ${frame.height}"
                            } else {
                                canvas.drawColor(AndroidColor.rgb(15, 23, 42))
                                canvas.drawText("ZESTO VIRTUAL CAMERA INJECTION", 40f, 80f, textPaint)
                                if (healthState == FrameHealthState.NO_FRAME) {
                                    canvas.drawText("STATUS: NO FRAME (AWAITING RTSP FEED)", 40f, 130f, textPaint)
                                    canvas.drawText("Start Zesto streaming service with valid RTSP URL", 40f, 180f, textPaint)
                                } else {
                                    canvas.drawText("STATUS: ${healthState.name}", 40f, 130f, textPaint)
                                    canvas.drawText("Delivered Frames: $frameCount", 40f, 180f, textPaint)
                                }
                            }
                        } finally {
                            tv.unlockCanvasAndPost(canvas)
                        }
                    }
                }
                delay(33L) // Polling interval
            }
        }
    }

    // Physical camera monitoring loop: sync state and update FPS cleanly
    LaunchedEffect(isVirtualInjectionActive, physicalFramesRendered) {
        if (!isVirtualInjectionActive) {
            frameCount = physicalFramesRendered
            healthState = if (physicalFramesRendered > 0) FrameHealthState.FRAME_ACTIVE else FrameHealthState.NO_FRAME
            activeResolution = "1280 x 720"
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ElegantDarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Target Camera Harness",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(ElegantDarkTertiary.copy(alpha = 0.2f))
                                .border(1.dp, ElegantDarkTertiary, RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "CAMERA2",
                                color = ElegantDarkTertiary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ElegantDarkPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ElegantDarkBackground)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Camera Preview Viewport Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.5.dp, if (isVirtualInjectionActive) ElegantDarkTertiary else ElegantDarkOutlineVariant, RoundedCornerShape(20.dp)),
                colors = CardDefaults.cardColors(containerColor = Color.Black)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AndroidView(
                        factory = { context ->
                            TextureView(context).apply {
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                                        textureViewRef = this@apply
                                        if (!isVirtualInjectionActive) {
                                            startCamera(this@apply)
                                        }
                                    }
                                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                        stopCamera()
                                        return true
                                    }
                                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                                        if (!isVirtualInjectionActive) {
                                            physicalFramesRendered++
                                            physicalFpsCounter++
                                            val now = System.currentTimeMillis()
                                            val elapsed = now - lastPhysicalFpsTimestamp
                                            if (elapsed >= 1000L) {
                                                measuredFps = (physicalFpsCounter * 1000.0) / elapsed
                                                physicalFpsCounter = 0L
                                                lastPhysicalFpsTimestamp = now
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Top HUD Badge
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
                                .border(1.dp, if (isVirtualInjectionActive) ElegantDarkTertiary else Color.White.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isVirtualInjectionActive) "SOURCE: ZESTO OBS INJECTION" else "SOURCE: PHYSICAL CAMERA2 SENSOR",
                                color = if (isVirtualInjectionActive) ElegantDarkTertiary else Color.White,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Bottom Health & FPS HUD Badge
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Health state indicator
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        when (healthState) {
                                            FrameHealthState.FRAME_ACTIVE -> Color(0xFF10B981).copy(alpha = 0.85f)
                                            FrameHealthState.FRAME_STALLED -> Color(0xFFF59E0B).copy(alpha = 0.85f)
                                            FrameHealthState.NO_FRAME -> Color(0xFFEF4444).copy(alpha = 0.85f)
                                        }
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = healthState.name,
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black.copy(alpha = 0.75f))
                                    .border(1.dp, ElegantDarkOutlineVariant, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "$activeResolution @ ${"%.1f".format(measuredFps)} FPS",
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            // Feed Switching Control Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, ElegantDarkOutlineVariant, RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = ElegantDarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "INTEGRATION SWITCH",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ElegantDarkPrimary,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Virtual Injected Feed",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = ElegantDarkTextPrimary
                            )
                            Text(
                                text = if (isVirtualInjectionActive) "Consuming OBS frames from ZestoFrameBridge" else "Consuming raw CameraDevice hardware feed",
                                style = MaterialTheme.typography.bodySmall,
                                color = ElegantDarkTextSecondary
                            )
                        }

                        Switch(
                            checked = isVirtualInjectionActive,
                            onCheckedChange = { active ->
                                isVirtualInjectionActive = active
                                if (active) {
                                    stopCamera()
                                } else {
                                    textureViewRef?.let { startCamera(it) }
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = ElegantDarkOnPrimary,
                                checkedTrackColor = ElegantDarkTertiary,
                                uncheckedThumbColor = ElegantDarkTextSecondary,
                                uncheckedTrackColor = ElegantDarkSurfaceVariant
                            )
                        )
                    }
                }
            }

            // Telemetry & Metrics Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, ElegantDarkOutlineVariant, RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = ElegantDarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "TARGET PIPELINE TELEMETRY",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ElegantDarkPrimary,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    HorizontalDivider(color = ElegantDarkOutlineVariant)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Active Pipeline", color = ElegantDarkTextSecondary, fontSize = 13.sp)
                        Text(if (isVirtualInjectionActive) "Zesto IPC Adapter" else "android.hardware.camera2", color = ElegantDarkTextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Frame Health State", color = ElegantDarkTextSecondary, fontSize = 13.sp)
                        Text(healthState.name, color = if (healthState == FrameHealthState.FRAME_ACTIVE) ElegantDarkTertiary else Color(0xFFEF4444), fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Frames Processed", color = ElegantDarkTextSecondary, fontSize = 13.sp)
                        Text("$frameCount", color = ElegantDarkTextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Dropped Frames", color = ElegantDarkTextSecondary, fontSize = 13.sp)
                        Text("$droppedFrames", color = if (droppedFrames > 0) Color(0xFFF59E0B) else ElegantDarkTextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Last Frame Age", color = ElegantDarkTextSecondary, fontSize = 13.sp)
                        Text(if (msSinceLastFrame >= 0) "${msSinceLastFrame}ms" else "N/A", color = ElegantDarkTextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Active Resolution", color = ElegantDarkTextSecondary, fontSize = 13.sp)
                        Text(activeResolution, color = ElegantDarkTextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Measured Framerate", color = ElegantDarkTextSecondary, fontSize = 13.sp)
                        Text("${"%.1f".format(measuredFps)} FPS", color = ElegantDarkTertiary, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
