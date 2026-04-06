package com.safewalker.ui

import android.util.Size
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.safewalker.detection.DangerAssessor
import com.safewalker.detection.ObjectDetectorHelper
import com.safewalker.model.DangerLevel
import com.safewalker.service.ObstacleDetectionService
import java.util.concurrent.Executors

/**
 * Full-screen camera preview with real-time detection overlay showing
 * bounding boxes, labels, and distance estimates.
 */
@Composable
fun CameraPreviewScreen(
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by ObstacleDetectionService.state.collectAsState()

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val dangerAssessor = remember { DangerAssessor() }
    val objectDetectorHelper = remember { ObjectDetectorHelper(context, dangerAssessor) }

    // Track overlay view reference for updates
    val overlayViewRef = remember { mutableListOf<DetectionOverlayView?>(null) }

    DisposableEffect(Unit) {
        ObstacleDetectionService.setPreviewActive(true)
        onDispose {
            ObstacleDetectionService.setPreviewActive(false)
            cameraExecutor.shutdown()
            objectDetectorHelper.close()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Camera preview + overlay
        AndroidView(
            factory = { ctx ->
                val frameLayout = android.widget.FrameLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }

                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }

                val overlayView = DetectionOverlayView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                overlayViewRef[0] = overlayView

                frameLayout.addView(previewView)
                frameLayout.addView(overlayView)

                // Set up CameraX
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    @Suppress("DEPRECATION")
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build()

                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        val imgWidth = imageProxy.width
                        val imgHeight = imageProxy.height

                        objectDetectorHelper.analyzeImage(imageProxy) { obstacles ->
                            // Update overlay on UI thread
                            overlayView.post {
                                overlayView.updateDetections(obstacles, imgWidth, imgHeight)
                            }

                            // Update shared state and trigger alerts via service
                            val maxDanger = obstacles.maxOfOrNull { it.dangerLevel.priority }
                                ?.let { p -> DangerLevel.entries.first { it.priority == p } }
                                ?: DangerLevel.NONE
                            val warningMessage = dangerAssessor.generateWarningMessage(obstacles)

                            val currentState = ObstacleDetectionService.state.value
                            ObstacleDetectionService.updateState(
                                currentState.copy(
                                    currentDangerLevel = maxDanger,
                                    obstacles = obstacles,
                                    lastWarningMessage = warningMessage,
                                    framesProcessed = currentState.framesProcessed + 1
                                )
                            )

                            // Trigger alerts through the service's AlertManager
                            ObstacleDetectionService.triggerAlert(maxDanger, warningMessage)
                        }
                    }

                    val cameraSelector = CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build()

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("CameraPreview", "Camera bind failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))

                frameLayout
            },
            modifier = Modifier.fillMaxSize()
        )

        // HUD overlay at the top
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(16.dp)
        ) {
            // Danger level banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when (state.currentDangerLevel) {
                            DangerLevel.HIGH -> Color(0xCC_D32F2F.toInt())
                            DangerLevel.MEDIUM -> Color(0xCC_F57C00.toInt())
                            DangerLevel.LOW -> Color(0xCC_FBC02D.toInt())
                            DangerLevel.NONE -> Color(0xCC_388E3C.toInt())
                        }
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = state.currentDangerLevel.label,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "${state.obstacles.size} objects",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Frame #${state.framesProcessed}",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Warning message
            if (state.lastWarningMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xCC000000))
                        .padding(12.dp)
                ) {
                    Text(
                        text = state.lastWarningMessage,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Obstacle list at the bottom
        if (state.obstacles.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC000000))
                    .padding(12.dp)
            ) {
                state.obstacles.take(4).forEach { obstacle ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    when (obstacle.dangerLevel) {
                                        DangerLevel.HIGH -> Color(0xFFF44336)
                                        DangerLevel.MEDIUM -> Color(0xFFFF9800)
                                        DangerLevel.LOW -> Color(0xFFFFEB3B)
                                        DangerLevel.NONE -> Color(0xFF4CAF50)
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = obstacle.label,
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = obstacle.dangerLevel.label,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "~${String.format("%.1f", obstacle.estimatedDistance)}m",
                            color = Color(0xFF90CAF9),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Stop button
        FloatingActionButton(
            onClick = onStop,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .size(64.dp),
            containerColor = Color(0xFFD32F2F),
            contentColor = Color.White,
            shape = CircleShape
        ) {
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = "Stop detection",
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
