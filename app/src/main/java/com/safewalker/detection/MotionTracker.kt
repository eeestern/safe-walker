package com.safewalker.detection

import android.graphics.RectF

/**
 * Tracks detected objects across consecutive frames to determine
 * motion characteristics: approach rate, velocity, and whether
 * an object is approaching, stationary, or receding.
 *
 * Objects are matched across frames using label + centroid proximity.
 */
class MotionTracker {

    /**
     * Per-object motion data computed by comparing consecutive frames.
     */
    data class MotionData(
        /** Rate at which the object's bounding box area is growing (positive = approaching).
         *  Expressed as fractional change per frame: (newSize - oldSize) / oldSize */
        val sizeChangeRate: Float = 0f,

        /** How many consecutive frames this object has been tracked. */
        val trackedFrames: Int = 0,

        /** True if the object is growing in the frame (getting closer). */
        val isApproaching: Boolean = false,

        /** True if the object is roughly the same size across frames (stationary relative to user). */
        val isStationary: Boolean = true,

        /** Smoothed approach score in [-1, 1]. Positive = approaching, negative = receding. */
        val approachScore: Float = 0f
    )

    private data class TrackedObject(
        val label: String,
        val box: RectF,
        val area: Float,
        val centerX: Float,
        val centerY: Float,
        val motionData: MotionData
    )

    private var previousObjects: List<TrackedObject> = emptyList()
    private var frameTimestamp: Long = 0L

    companion object {
        /** Max centroid distance (as fraction of image diagonal) to consider same object. */
        private const val MAX_MATCH_DISTANCE = 0.25f

        /** Size change below this threshold (per frame) is considered stationary. */
        private const val STATIONARY_THRESHOLD = 0.03f

        /** Size change above this threshold (per frame) is considered approaching. */
        private const val APPROACH_THRESHOLD = 0.02f

        /** Exponential smoothing factor for approach score. */
        private const val SMOOTHING_ALPHA = 0.4f

        /** After this many stationary frames, demote danger. */
        const val STATIONARY_FRAMES_THRESHOLD = 5
    }

    /**
     * Process a new frame's detections and return motion data for each object.
     * The returned list is in the same order as the input lists.
     */
    fun processFrame(
        labels: List<String>,
        boundingBoxes: List<RectF>,
        imageWidth: Int,
        imageHeight: Int
    ): List<MotionData> {
        val imageDiagonal = Math.sqrt(
            (imageWidth * imageWidth + imageHeight * imageHeight).toDouble()
        ).toFloat()
        val imageArea = imageWidth.toFloat() * imageHeight.toFloat()

        val currentObjects = labels.zip(boundingBoxes).map { (label, box) ->
            val area = box.width() * box.height()
            TrackedObject(
                label = label,
                box = box,
                area = area,
                centerX = box.centerX(),
                centerY = box.centerY(),
                motionData = MotionData() // placeholder, computed below
            )
        }

        // Match current objects to previous frame objects
        val motionResults = mutableListOf<MotionData>()
        val usedPrevious = mutableSetOf<Int>()

        for (current in currentObjects) {
            val bestMatch = findBestMatch(current, previousObjects, usedPrevious, imageDiagonal)

            if (bestMatch != null) {
                usedPrevious.add(previousObjects.indexOf(bestMatch))

                // Calculate size change rate
                val sizeChange = if (bestMatch.area > 0) {
                    (current.area - bestMatch.area) / bestMatch.area
                } else {
                    0f
                }

                val isStationary = Math.abs(sizeChange) < STATIONARY_THRESHOLD
                val isApproaching = sizeChange > APPROACH_THRESHOLD

                // Smooth the approach score
                val rawScore = sizeChange.coerceIn(-1f, 1f)
                val smoothedScore = SMOOTHING_ALPHA * rawScore +
                        (1 - SMOOTHING_ALPHA) * bestMatch.motionData.approachScore

                val trackedFrames = if (isStationary) {
                    bestMatch.motionData.trackedFrames + 1
                } else {
                    // Reset stationary counter when motion detected
                    0
                }

                motionResults.add(
                    MotionData(
                        sizeChangeRate = sizeChange,
                        trackedFrames = trackedFrames,
                        isApproaching = isApproaching,
                        isStationary = isStationary,
                        approachScore = smoothedScore
                    )
                )
            } else {
                // New object, no motion data yet
                motionResults.add(MotionData())
            }
        }

        // Save current frame for next comparison
        previousObjects = currentObjects.zip(motionResults).map { (obj, motion) ->
            obj.copy(motionData = motion)
        }
        frameTimestamp = System.currentTimeMillis()

        return motionResults
    }

    /**
     * Find the best matching object from the previous frame.
     * Match criteria: same label + closest centroid within max distance.
     */
    private fun findBestMatch(
        current: TrackedObject,
        previous: List<TrackedObject>,
        usedIndices: Set<Int>,
        imageDiagonal: Float
    ): TrackedObject? {
        var bestMatch: TrackedObject? = null
        var bestDistance = Float.MAX_VALUE

        for ((index, prev) in previous.withIndex()) {
            if (index in usedIndices) continue
            if (prev.label != current.label) continue

            val dx = current.centerX - prev.centerX
            val dy = current.centerY - prev.centerY
            val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            val normalizedDistance = distance / imageDiagonal

            if (normalizedDistance < MAX_MATCH_DISTANCE && distance < bestDistance) {
                bestDistance = distance
                bestMatch = prev
            }
        }

        return bestMatch
    }

    /** Clear all tracking state (e.g. when detection stops/restarts). */
    fun reset() {
        previousObjects = emptyList()
        frameTimestamp = 0L
    }
}
