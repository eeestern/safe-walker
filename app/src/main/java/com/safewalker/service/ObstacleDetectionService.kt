package com.safewalker.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.safewalker.MainActivity
import com.safewalker.alert.AlertManager
import com.safewalker.alert.AlertManager.Companion.DETECTION_CHANNEL_ID
import com.safewalker.alert.AlertManager.Companion.DETECTION_NOTIFICATION_ID
import com.safewalker.detection.DangerAssessor
import com.safewalker.detection.DepthEstimator
import com.safewalker.detection.ObjectDetectorHelper
import com.safewalker.model.DangerLevel
import com.safewalker.model.DetectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that uses CameraX + ML Kit to detect obstacles
 * and alert the user via the AlertManager.
 *
 * When the activity is in the foreground and showing the camera preview,
 * the service defers camera binding to the activity (preview mode).
 * When the activity goes to the background, the service resumes its own
 * camera binding for analysis-only detection.
 */
class ObstacleDetectionService : Service(), LifecycleOwner {

    private lateinit var lifecycleRegistry: LifecycleRegistry
    private lateinit var alertManager: AlertManager
    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private lateinit var dangerAssessor: DangerAssessor
    private lateinit var depthEstimator: DepthEstimator
    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    companion object {
        private const val TAG = "ObstacleDetectionSvc"
        private val _state = MutableStateFlow(DetectionState())
        val state: StateFlow<DetectionState> = _state.asStateFlow()

        private var previewActive = false
        private var instance: ObstacleDetectionService? = null

        fun isRunning(): Boolean = _state.value.isRunning

        fun updateState(newState: DetectionState) {
            _state.value = newState
        }

        fun setPreviewActive(active: Boolean) {
            previewActive = active
            if (!active) {
                instance?.resumeServiceCamera()
            } else {
                instance?.pauseServiceCamera()
            }
        }

        /**
         * Called from the camera preview to trigger alerts via the service's AlertManager.
         */
        fun triggerAlert(dangerLevel: DangerLevel, message: String) {
            instance?.triggerAlert(dangerLevel, message)
        }

        /**
         * Returns the service's DepthEstimator so the camera preview can
         * share it instead of creating a conflicting one.
         */
        fun getDepthEstimator(): DepthEstimator? = instance?.depthEstimator
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        alertManager = AlertManager(this)
        depthEstimator = DepthEstimator(this)
        depthEstimator.initialize()
        dangerAssessor = DangerAssessor(depthEstimator)
        objectDetectorHelper = ObjectDetectorHelper(this, dangerAssessor)
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(DETECTION_NOTIFICATION_ID, createForegroundNotification())
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        _state.value = DetectionState(isRunning = true)
        depthEstimator.startDepthCamera()
        if (!previewActive) {
            startCamera()
        }
        return START_STICKY
    }

    private fun createForegroundNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, DETECTION_CHANNEL_ID)
            .setContentTitle("SafeWalker Active")
            .setContentText("Scanning for obstacles...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    fun triggerAlert(dangerLevel: DangerLevel, message: String) {
        alertManager.alert(dangerLevel, message)
    }

    private fun pauseServiceCamera() {
        cameraProvider?.unbindAll()
        Log.i(TAG, "Service camera paused — activity preview is active")
    }

    private fun resumeServiceCamera() {
        if (_state.value.isRunning) {
            depthEstimator.startDepthCamera()
            startCamera()
            Log.i(TAG, "Service camera resumed — activity preview is inactive")
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                if (!previewActive) {
                    bindAnalysis()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera provider failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @Suppress("DEPRECATION")
    private fun bindAnalysis() {
        val provider = cameraProvider ?: return

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor!!) { imageProxy ->
            objectDetectorHelper.analyzeImage(imageProxy) { obstacles ->
                val maxDanger = obstacles.maxOfOrNull { it.dangerLevel.priority }
                    ?.let { p -> DangerLevel.entries.first { it.priority == p } }
                    ?: DangerLevel.NONE

                val warningMessage = dangerAssessor.generateWarningMessage(obstacles)

                _state.value = _state.value.copy(
                    currentDangerLevel = maxDanger,
                    obstacles = obstacles,
                    lastWarningMessage = warningMessage,
                    framesProcessed = _state.value.framesProcessed + 1
                )

                alertManager.alert(maxDanger, warningMessage)
            }
        }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, cameraSelector, imageAnalysis)
            Log.i(TAG, "Camera bound for analysis")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        cameraProvider?.unbindAll()
        cameraExecutor?.shutdown()
        objectDetectorHelper.close()
        depthEstimator.shutdown()
        alertManager.shutdown()
        _state.value = DetectionState(isRunning = false)
        super.onDestroy()
    }
}
