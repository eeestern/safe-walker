package com.safewalker.detection

import android.content.Context
import android.graphics.RectF
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.safewalker.model.DetectedObstacle

/**
 * Wraps Google ML Kit Object Detection for use with CameraX image analysis.
 */
class ObjectDetectorHelper(
    context: Context,
    private val dangerAssessor: DangerAssessor = DangerAssessor()
) {
    private val detector: ObjectDetector

    init {
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()

        detector = ObjectDetection.getClient(options)
    }

    @OptIn(ExperimentalGetImage::class)
    fun analyzeImage(
        imageProxy: ImageProxy,
        onResults: (List<DetectedObstacle>) -> Unit
    ) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        detector.process(inputImage)
            .addOnSuccessListener { detectedObjects ->
                val labels = mutableListOf<Pair<String, Float>>()
                val boxes = mutableListOf<RectF>()

                for (obj in detectedObjects) {
                    val box = RectF(obj.boundingBox)
                    // ML Kit may return multiple labels per object; use the top one
                    val topLabel = obj.labels.maxByOrNull { it.confidence }
                    val labelText = topLabel?.text ?: "Unknown"
                    val confidence = topLabel?.confidence ?: 0.5f

                    labels.add(Pair(labelText, confidence))
                    boxes.add(box)
                }

                val obstacles = dangerAssessor.assessObstacles(
                    labels = labels,
                    boundingBoxes = boxes,
                    imageWidth = inputImage.width,
                    imageHeight = inputImage.height
                )
                onResults(obstacles)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Object detection failed", e)
                onResults(emptyList())
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    fun close() {
        detector.close()
    }

    companion object {
        private const val TAG = "ObjectDetectorHelper"
    }
}
