package com.safewalker.detection

import android.graphics.RectF
import com.safewalker.model.DangerLevel
import com.safewalker.model.DetectedObstacle
import com.safewalker.model.ObstacleCategory

/**
 * Assesses danger level from detected objects based on their position,
 * size, classification, AND motion (approach rate).
 *
 * Motion-aware algorithm:
 * 1. Compute a "base" danger level from position + size + category
 * 2. Track objects across frames via MotionTracker to get approach rate
 * 3. Adjust danger level based on motion:
 *    - Objects that are APPROACHING (growing in frame) -> maintain or escalate base danger
 *    - Objects that are STATIONARY for several frames -> demote danger by 1-2 levels
 *    - Objects that are RECEDING (shrinking in frame) -> demote to NONE
 * 4. Exception: very close objects (>40% of frame) always stay HIGH regardless of motion,
 *    because you're about to collide even if nobody is moving
 */
class DangerAssessor(
    private val depthEstimator: DepthEstimator? = null
) {

    private val motionTracker = MotionTracker()

    companion object {
        // Labels that indicate street/road (from ML Kit default labels)
        private val STREET_LABELS = setOf(
            "car", "truck", "bus", "motorcycle", "bicycle",
            "vehicle", "traffic light", "stop sign"
        )

        // Labels for trip hazards (low-lying objects)
        private val TRIP_LABELS = setOf(
            "backpack", "suitcase", "bottle", "cup", "bowl",
            "skateboard", "sports ball", "hydrant", "cone"
        )

        // Labels for large collision obstacles
        private val COLLISION_LABELS = setOf(
            "person", "chair", "bench", "table", "pole",
            "door", "wall", "tree", "pillar", "post"
        )

        // Fraction of image area that triggers different danger levels
        private const val LARGE_OBJECT_THRESHOLD = 0.35f  // >35% of frame = very close
        private const val MEDIUM_OBJECT_THRESHOLD = 0.20f // >20% of frame = approaching
        private const val CENTER_ZONE_MARGIN = 0.25f       // center 50% of frame

        // Imminent collision: even stationary objects at this size are dangerous
        private const val IMMINENT_COLLISION_THRESHOLD = 0.40f
    }

    fun assessObstacles(
        labels: List<Pair<String, Float>>, // label, confidence pairs
        boundingBoxes: List<RectF>,
        imageWidth: Int,
        imageHeight: Int
    ): List<DetectedObstacle> {
        if (labels.size != boundingBoxes.size) return emptyList()

        // Get motion data from tracker
        val labelStrings = labels.map { it.first }
        val motionDataList = motionTracker.processFrame(
            labelStrings, boundingBoxes, imageWidth, imageHeight
        )

        return labels.zip(boundingBoxes).zip(motionDataList).map { (labelBox, motion) ->
            val (labelPair, box) = labelBox
            val (label, confidence) = labelPair
            val category = categorize(label)
            val relativeSize = calculateRelativeSize(box, imageWidth, imageHeight)
            val isInCenter = isInCenterZone(box, imageWidth)
            val isLowInFrame = isLowInFrame(box, imageHeight)
            // Use DepthEstimator for distance (falls back to bounding box)
            val depthResult = depthEstimator?.estimateDistance(box, label, imageWidth, imageHeight)
            val estimatedDistance = depthResult?.distanceMeters ?: estimateDistance(relativeSize)
            val depthSource = depthResult?.source?.displayName ?: "Est."

            // Step 1: compute base danger from position/size/category
            val baseDanger = assessBaseDanger(category, relativeSize, isInCenter, isLowInFrame)

            // Step 2: adjust danger based on motion
            val adjustedDanger = adjustForMotion(baseDanger, motion, relativeSize)

            DetectedObstacle(
                label = label,
                confidence = confidence,
                boundingBox = box,
                category = category,
                dangerLevel = adjustedDanger,
                estimatedDistance = estimatedDistance,
                depthSource = depthSource,
                approachScore = motion.approachScore,
                isApproaching = motion.isApproaching,
                isStationary = motion.isStationary,
                stationaryFrames = motion.trackedFrames
            )
        }.sortedByDescending { it.dangerLevel.priority }
    }

    /**
     * Adjust the base danger level based on motion tracking data.
     *
     * Rules:
     * - IMMINENT collision (very large in frame): always HIGH, motion irrelevant
     * - APPROACHING (object growing): keep base danger or escalate by 1 level
     * - STATIONARY for many frames: demote by 1-2 levels
     * - RECEDING (object shrinking): demote to NONE
     */
    private fun adjustForMotion(
        baseDanger: DangerLevel,
        motion: MotionTracker.MotionData,
        relativeSize: Float
    ): DangerLevel {
        // Imminent collision override: extremely close objects are always HIGH
        if (relativeSize > IMMINENT_COLLISION_THRESHOLD) {
            return DangerLevel.HIGH
        }

        // Receding objects are not a threat
        if (motion.approachScore < -0.05f && !motion.isStationary) {
            return DangerLevel.NONE
        }

        // Approaching objects: maintain or escalate danger
        if (motion.isApproaching) {
            return when (baseDanger) {
                DangerLevel.NONE -> DangerLevel.LOW
                DangerLevel.LOW -> DangerLevel.MEDIUM
                DangerLevel.MEDIUM -> DangerLevel.HIGH
                DangerLevel.HIGH -> DangerLevel.HIGH
            }
        }

        // Stationary objects: demote danger over time
        if (motion.isStationary && motion.trackedFrames >= MotionTracker.STATIONARY_FRAMES_THRESHOLD) {
            // Strongly stationary (tracked for many frames) -> demote by 2
            val demoteBy = if (motion.trackedFrames >= MotionTracker.STATIONARY_FRAMES_THRESHOLD * 2) 2 else 1
            return demoteDanger(baseDanger, demoteBy)
        }

        // Default: trust the base danger (new object or just a few frames)
        return baseDanger
    }

    /** Demote danger level by the given number of steps (min NONE). */
    private fun demoteDanger(level: DangerLevel, steps: Int): DangerLevel {
        val newPriority = (level.priority - steps).coerceAtLeast(0)
        return DangerLevel.entries.first { it.priority == newPriority }
    }

    private fun categorize(label: String): ObstacleCategory {
        val lowerLabel = label.lowercase()
        return when {
            STREET_LABELS.any { lowerLabel.contains(it) } -> ObstacleCategory.STREET_DANGER
            TRIP_LABELS.any { lowerLabel.contains(it) } -> ObstacleCategory.TRIP_HAZARD
            COLLISION_LABELS.any { lowerLabel.contains(it) } -> ObstacleCategory.COLLISION_RISK
            else -> ObstacleCategory.GENERAL
        }
    }

    private fun calculateRelativeSize(box: RectF, imageWidth: Int, imageHeight: Int): Float {
        val boxArea = box.width() * box.height()
        val imageArea = imageWidth.toFloat() * imageHeight.toFloat()
        return if (imageArea > 0) boxArea / imageArea else 0f
    }

    private fun isInCenterZone(box: RectF, imageWidth: Int): Boolean {
        val centerX = box.centerX()
        val leftBound = imageWidth * CENTER_ZONE_MARGIN
        val rightBound = imageWidth * (1 - CENTER_ZONE_MARGIN)
        return centerX in leftBound..rightBound
    }

    private fun isLowInFrame(box: RectF, imageHeight: Int): Boolean {
        return box.bottom > imageHeight * 0.66f
    }

    /**
     * Base danger assessment from position, size, and category ONLY (no motion).
     */
    private fun assessBaseDanger(
        category: ObstacleCategory,
        relativeSize: Float,
        isInCenter: Boolean,
        isLowInFrame: Boolean
    ): DangerLevel {
        // Street dangers are always at least MEDIUM
        if (category == ObstacleCategory.STREET_DANGER) {
            return if (relativeSize > MEDIUM_OBJECT_THRESHOLD || isInCenter) {
                DangerLevel.HIGH
            } else {
                DangerLevel.MEDIUM
            }
        }

        // Large objects directly ahead = HIGH danger
        if (relativeSize > LARGE_OBJECT_THRESHOLD && isInCenter) {
            return DangerLevel.HIGH
        }

        // Trip hazards that are close and in path
        if (category == ObstacleCategory.TRIP_HAZARD && isLowInFrame && isInCenter) {
            return if (relativeSize > MEDIUM_OBJECT_THRESHOLD) DangerLevel.HIGH else DangerLevel.MEDIUM
        }

        // Medium-sized objects in center
        if (relativeSize > MEDIUM_OBJECT_THRESHOLD && isInCenter) {
            return DangerLevel.MEDIUM
        }

        // Objects in center but small
        if (isInCenter && relativeSize > 0.05f) {
            return DangerLevel.LOW
        }

        // Objects off to the side or very small
        return if (relativeSize > MEDIUM_OBJECT_THRESHOLD) DangerLevel.LOW else DangerLevel.NONE
    }

    private fun estimateDistance(relativeSize: Float): Float {
        return when {
            relativeSize > LARGE_OBJECT_THRESHOLD -> 0.5f
            relativeSize > MEDIUM_OBJECT_THRESHOLD -> 1.5f
            relativeSize > 0.05f -> 3.0f
            else -> 5.0f
        }
    }

    fun generateWarningMessage(obstacles: List<DetectedObstacle>): String {
        val highDanger = obstacles.filter { it.dangerLevel == DangerLevel.HIGH }
        val mediumDanger = obstacles.filter { it.dangerLevel == DangerLevel.MEDIUM }

        return when {
            highDanger.isNotEmpty() -> {
                val topThreat = highDanger.first()
                val motionSuffix = if (topThreat.isApproaching) " Approaching fast!" else ""
                when (topThreat.category) {
                    ObstacleCategory.STREET_DANGER -> "Warning! Vehicle or road ahead!$motionSuffix"
                    ObstacleCategory.TRIP_HAZARD -> "Watch out! Trip hazard ahead!$motionSuffix"
                    ObstacleCategory.COLLISION_RISK -> "Stop! ${topThreat.label} directly ahead!$motionSuffix"
                    ObstacleCategory.GENERAL -> "Danger! Obstacle ahead!$motionSuffix"
                }
            }
            mediumDanger.isNotEmpty() -> {
                val topThreat = mediumDanger.first()
                val motionSuffix = if (topThreat.isApproaching) " Getting closer." else ""
                when (topThreat.category) {
                    ObstacleCategory.STREET_DANGER -> "Caution: approaching road or traffic.$motionSuffix"
                    ObstacleCategory.TRIP_HAZARD -> "Caution: obstacle on the ground nearby.$motionSuffix"
                    ObstacleCategory.COLLISION_RISK -> "Caution: ${topThreat.label} nearby.$motionSuffix"
                    ObstacleCategory.GENERAL -> "Caution: obstacle nearby.$motionSuffix"
                }
            }
            else -> ""
        }
    }
}
