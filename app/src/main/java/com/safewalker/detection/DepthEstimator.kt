package com.safewalker.detection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.RectF
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Estimates distance to detected objects using the best available hardware:
 *
 * 1. **Depth sensor** (ToF / structured light / LiDAR) — real metric depth
 *    from a hardware depth camera. Samples the depth buffer at the bounding
 *    box center for each detected object.
 *
 * 2. **Camera intrinsics** (focal length + known object sizes) — uses the
 *    pinhole camera model: distance = (realHeight * focalLengthPx) / boxHeightPx.
 *    Much more accurate than pure bounding-box-area estimation because it
 *    uses the camera's actual optical parameters.
 *
 * 3. **Bounding box fallback** — rough estimation from relative frame area.
 *    Used only when no camera metadata is available.
 */
class DepthEstimator(private val context: Context) {

    enum class DepthSource(val displayName: String) {
        DEPTH_SENSOR("Depth"),
        CAMERA_INTRINSICS("Optics"),
        BOUNDING_BOX("Est.")
    }

    data class DepthResult(
        val distanceMeters: Float,
        val source: DepthSource
    )

    // Camera intrinsics
    private var focalLengthMm: Float = 0f
    private var sensorHeightMm: Float = 0f
    private var hasIntrinsics = false

    // Depth sensor
    private var depthCameraId: String? = null
    private var depthCameraDevice: CameraDevice? = null
    private var depthSession: CameraCaptureSession? = null
    private var depthImageReader: ImageReader? = null
    private var latestDepthBuffer: ShortArray? = null
    private var depthWidth: Int = 0
    private var depthHeight: Int = 0
    private val depthLock = Object()
    private var depthSensorActive = false
    private var depthHandlerThread: HandlerThread? = null
    private var depthHandler: Handler? = null

    var hasDepthCamera = false
        private set
    var hasMultiCamera = false
        private set

    companion object {
        private const val TAG = "DepthEstimator"

        // Approximate real-world heights in meters for pinhole model
        private val KNOWN_HEIGHTS = mapOf(
            "person" to 1.7f,
            "car" to 1.5f,
            "truck" to 2.8f,
            "bus" to 3.0f,
            "motorcycle" to 1.1f,
            "bicycle" to 1.0f,
            "chair" to 0.9f,
            "table" to 0.75f,
            "bench" to 0.5f,
            "backpack" to 0.5f,
            "suitcase" to 0.7f,
            "bottle" to 0.25f,
            "cup" to 0.12f,
            "bowl" to 0.10f,
            "door" to 2.0f,
            "wall" to 2.5f,
            "tree" to 3.0f,
            "pole" to 2.5f,
            "pillar" to 2.5f,
            "post" to 1.5f,
            "hydrant" to 0.6f,
            "cone" to 0.7f,
            "skateboard" to 0.15f,
            "sports ball" to 0.22f,
            "traffic light" to 1.0f,
            "stop sign" to 0.75f,
            "vehicle" to 1.5f,
            "Fashion good" to 0.5f,
            "Home good" to 0.5f,
            "Food" to 0.2f,
            "Place" to 2.0f,
            "Plant" to 1.0f
        )
        private const val DEFAULT_OBJECT_HEIGHT = 0.5f
        private const val LARGE_THRESHOLD = 0.35f
        private const val MEDIUM_THRESHOLD = 0.20f
    }

    /**
     * Probe the device for depth-capable cameras and camera intrinsics.
     * Call this once during initialization.
     */
    fun initialize() {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)

            if (capabilities != null &&
                capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)
            ) {
                depthCameraId = id
                hasDepthCamera = true
                Log.i(TAG, "Found depth camera: ID=$id")
            }

            if (capabilities != null &&
                capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            ) {
                hasMultiCamera = true
                Log.i(TAG, "Found multi-camera: ID=$id")
            }

            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK && !hasIntrinsics) {
                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                if (focalLengths != null && sensorSize != null && focalLengths.isNotEmpty()) {
                    focalLengthMm = focalLengths[0]
                    sensorHeightMm = sensorSize.height
                    hasIntrinsics = true
                    Log.i(TAG, "Camera intrinsics: focal=${focalLengthMm}mm, sensorH=${sensorHeightMm}mm")
                }
            }
        }

        Log.i(TAG, "Depth capabilities: depthCamera=$hasDepthCamera, multiCamera=$hasMultiCamera, intrinsics=$hasIntrinsics")
    }

    /**
     * Start capturing from the depth camera (if available).
     * Must be called after camera permission is granted.
     */
    @Suppress("MissingPermission")
    fun startDepthCamera() {
        val camId = depthCameraId ?: return
        if (depthSensorActive) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Camera permission not granted, cannot start depth camera")
            return
        }

        depthHandlerThread = HandlerThread("DepthCamera").also { it.start() }
        depthHandler = Handler(depthHandlerThread!!.looper)

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            val chars = cameraManager.getCameraCharacteristics(camId)
            val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val depthSizes = streamMap?.getOutputSizes(ImageFormat.DEPTH16)

            if (depthSizes.isNullOrEmpty()) {
                Log.w(TAG, "Depth camera has no DEPTH16 output sizes")
                return
            }

            val depthSize = depthSizes.minByOrNull { it.width * it.height } ?: depthSizes[0]
            depthWidth = depthSize.width
            depthHeight = depthSize.height

            depthImageReader = ImageReader.newInstance(
                depthWidth, depthHeight, ImageFormat.DEPTH16, 2
            ).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val shortBuffer = buffer.asShortBuffer()
                        val depthData = ShortArray(shortBuffer.remaining())
                        shortBuffer.get(depthData)
                        synchronized(depthLock) {
                            latestDepthBuffer = depthData
                        }
                    } finally {
                        image.close()
                    }
                }, depthHandler)
            }

            cameraManager.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    depthCameraDevice = camera
                    createDepthCaptureSession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    depthCameraDevice = null
                    depthSensorActive = false
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Depth camera error: $error")
                    camera.close()
                    depthCameraDevice = null
                    depthSensorActive = false
                }
            }, depthHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start depth camera", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun createDepthCaptureSession(camera: CameraDevice) {
        val reader = depthImageReader ?: return

        try {
            camera.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        depthSession = session
                        depthSensorActive = true

                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(reader.surface)
                        }.build()

                        session.setRepeatingRequest(request, null, depthHandler)
                        Log.i(TAG, "Depth camera streaming: ${depthWidth}x${depthHeight}")
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Depth capture session configuration failed")
                        depthSensorActive = false
                    }
                },
                depthHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create depth capture session", e)
        }
    }

    /**
     * Estimate distance to an object given its bounding box and label.
     * Tries depth sensor first, then camera intrinsics, then bounding box fallback.
     */
    fun estimateDistance(
        boundingBox: RectF,
        label: String,
        imageWidth: Int,
        imageHeight: Int
    ): DepthResult {
        // Tier 1: Depth sensor
        if (depthSensorActive) {
            val depth = sampleDepthAtBox(boundingBox, imageWidth, imageHeight)
            if (depth != null && depth > 0.05f && depth < 20f) {
                return DepthResult(depth, DepthSource.DEPTH_SENSOR)
            }
        }

        // Tier 2: Camera intrinsics (pinhole model)
        if (hasIntrinsics) {
            val distance = estimateWithIntrinsics(boundingBox, label, imageWidth, imageHeight)
            if (distance > 0f) {
                return DepthResult(distance, DepthSource.CAMERA_INTRINSICS)
            }
        }

        // Tier 3: Bounding box fallback
        return DepthResult(
            estimateFromBoundingBox(boundingBox, imageWidth, imageHeight),
            DepthSource.BOUNDING_BOX
        )
    }

    /**
     * Sample depth from the depth buffer at the center of the bounding box.
     * Maps RGB image coordinates to depth image coordinates.
     * Returns distance in meters, or null if unavailable.
     */
    private fun sampleDepthAtBox(
        boundingBox: RectF,
        imageWidth: Int,
        imageHeight: Int
    ): Float? {
        val buffer: ShortArray
        synchronized(depthLock) {
            buffer = latestDepthBuffer ?: return null
        }

        val centerX = boundingBox.centerX() / imageWidth
        val centerY = boundingBox.centerY() / imageHeight

        val depthX = (centerX * depthWidth).toInt().coerceIn(0, depthWidth - 1)
        val depthY = (centerY * depthHeight).toInt().coerceIn(0, depthHeight - 1)

        // Sample a small region around the center for robustness
        val sampleRadius = 2
        var totalDepth = 0f
        var validSamples = 0

        for (dy in -sampleRadius..sampleRadius) {
            for (dx in -sampleRadius..sampleRadius) {
                val sx = (depthX + dx).coerceIn(0, depthWidth - 1)
                val sy = (depthY + dy).coerceIn(0, depthHeight - 1)
                val index = sy * depthWidth + sx

                if (index in buffer.indices) {
                    val raw = buffer[index]
                    // DEPTH16 format: lower 13 bits = depth in mm, upper 3 bits = confidence
                    val depthMm = (raw.toInt() and 0x1FFF)
                    val confidence = (raw.toInt() shr 13) and 0x07

                    if (depthMm > 0 && confidence > 0) {
                        totalDepth += depthMm / 1000f
                        validSamples++
                    }
                }
            }
        }

        return if (validSamples > 0) totalDepth / validSamples else null
    }

    /**
     * Estimate distance using the pinhole camera model:
     *   distance = (realObjectHeight * focalLengthPx) / boundingBoxHeightPx
     */
    private fun estimateWithIntrinsics(
        boundingBox: RectF,
        label: String,
        imageWidth: Int,
        imageHeight: Int
    ): Float {
        if (sensorHeightMm <= 0) return -1f

        val boxHeightPx = boundingBox.height()
        if (boxHeightPx <= 0) return -1f

        val focalLengthPx = (focalLengthMm / sensorHeightMm) * imageHeight
        val realHeight = lookupObjectHeight(label)
        val distance = (realHeight * focalLengthPx) / boxHeightPx

        return distance.coerceIn(0.1f, 30f)
    }

    private fun lookupObjectHeight(label: String): Float {
        KNOWN_HEIGHTS[label]?.let { return it }

        val lower = label.lowercase()
        for ((key, height) in KNOWN_HEIGHTS) {
            if (lower.contains(key.lowercase())) return height
        }

        return DEFAULT_OBJECT_HEIGHT
    }

    private fun estimateFromBoundingBox(
        boundingBox: RectF,
        imageWidth: Int,
        imageHeight: Int
    ): Float {
        val boxArea = boundingBox.width() * boundingBox.height()
        val imageArea = imageWidth.toFloat() * imageHeight.toFloat()
        val relativeSize = if (imageArea > 0) boxArea / imageArea else 0f

        return when {
            relativeSize > LARGE_THRESHOLD -> 0.5f
            relativeSize > MEDIUM_THRESHOLD -> 1.5f
            relativeSize > 0.05f -> 3.0f
            else -> 5.0f
        }
    }

    fun getCapabilitySummary(): String {
        val sources = mutableListOf<String>()
        if (hasDepthCamera) sources.add("Depth sensor (ToF/LiDAR)")
        if (hasMultiCamera) sources.add("Multi-camera")
        if (hasIntrinsics) sources.add("Camera optics (f=${String.format("%.1f", focalLengthMm)}mm)")
        if (sources.isEmpty()) sources.add("Bounding box estimation only")
        return sources.joinToString(", ")
    }

    fun getActiveSource(): DepthSource {
        return when {
            depthSensorActive -> DepthSource.DEPTH_SENSOR
            hasIntrinsics -> DepthSource.CAMERA_INTRINSICS
            else -> DepthSource.BOUNDING_BOX
        }
    }

    fun shutdown() {
        depthSession?.close()
        depthCameraDevice?.close()
        depthImageReader?.close()
        depthHandlerThread?.quitSafely()
        depthSession = null
        depthCameraDevice = null
        depthImageReader = null
        depthHandlerThread = null
        depthHandler = null
        latestDepthBuffer = null
        depthSensorActive = false
    }
}
