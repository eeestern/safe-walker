package com.safewalker.detection

import android.graphics.RectF
import com.safewalker.model.DangerLevel
import com.safewalker.model.DetectedObstacle
import com.safewalker.model.ObstacleCategory

/**
 * Assesses danger level from detected objects based on their position,
 * size, and classification.
 */
class DangerAssessor {

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

        // Fraction of image width/height that triggers different danger levels
        private const val LARGE_OBJECT_THRESHOLD = 0.35f  // >35% of frame = very close
        private const val MEDIUM_OBJECT_THRESHOLD = 0.20f // >20% of frame = approaching
        private const val CENTER_ZONE_MARGIN = 0.25f       // center 50% of frame
    }

    fun assessObstacles(
        labels: List<Pair<String, Float>>, // label, confidence pairs
        boundingBoxes: List<RectF>,
        imageWidth: Int,
        imageHeight: Int
    ): List<DetectedObstacle> {
        if (labels.size != boundingBoxes.size) return emptyList()

        return labels.zip(boundingBoxes).map { (labelPair, box) ->
            val (label, confidence) = labelPair
            val category = categorize(label)
            val relativeSize = calculateRelativeSize(box, imageWidth, imageHeight)
            val isInCenter = isInCenterZone(box, imageWidth)
            val isLowInFrame = isLowInFrame(box, imageHeight)
            val dangerLevel = assessDanger(category, relativeSize, isInCenter, isLowInFrame)
            val estimatedDistance = estimateDistance(relativeSize)

            DetectedObstacle(
                label = label,
                confidence = confidence,
                boundingBox = box,
                category = category,
                dangerLevel = dangerLevel,
                estimatedDistance = estimatedDistance
            )
        }.sortedByDescending { it.dangerLevel.priority }
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
        // Objects in the bottom third of the frame are "low" — potential trip hazards
        return box.bottom > imageHeight * 0.66f
    }

    private fun assessDanger(
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
        // Very rough distance estimation based on how much of the frame the object occupies
        // Larger relative size = closer object
        return when {
            relativeSize > LARGE_OBJECT_THRESHOLD -> 0.5f  // ~0.5 meters
            relativeSize > MEDIUM_OBJECT_THRESHOLD -> 1.5f // ~1.5 meters
            relativeSize > 0.05f -> 3.0f                    // ~3 meters
            else -> 5.0f                                     // ~5+ meters
        }
    }

    fun generateWarningMessage(obstacles: List<DetectedObstacle>): String {
        val highDanger = obstacles.filter { it.dangerLevel == DangerLevel.HIGH }
        val mediumDanger = obstacles.filter { it.dangerLevel == DangerLevel.MEDIUM }

        return when {
            highDanger.isNotEmpty() -> {
                val topThreat = highDanger.first()
                when (topThreat.category) {
                    ObstacleCategory.STREET_DANGER -> "Warning! Vehicle or road ahead!"
                    ObstacleCategory.TRIP_HAZARD -> "Watch out! Trip hazard ahead!"
                    ObstacleCategory.COLLISION_RISK -> "Stop! ${topThreat.label} directly ahead!"
                    ObstacleCategory.GENERAL -> "Danger! Obstacle ahead!"
                }
            }
            mediumDanger.isNotEmpty() -> {
                val topThreat = mediumDanger.first()
                when (topThreat.category) {
                    ObstacleCategory.STREET_DANGER -> "Caution: approaching road or traffic."
                    ObstacleCategory.TRIP_HAZARD -> "Caution: obstacle on the ground nearby."
                    ObstacleCategory.COLLISION_RISK -> "Caution: ${topThreat.label} nearby."
                    ObstacleCategory.GENERAL -> "Caution: obstacle nearby."
                }
            }
            else -> ""
        }
    }
}
