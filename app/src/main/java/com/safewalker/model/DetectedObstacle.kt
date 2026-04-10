package com.safewalker.model

import android.graphics.RectF

data class DetectedObstacle(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF,
    val category: ObstacleCategory,
    val dangerLevel: DangerLevel,
    val estimatedDistance: Float,
    val depthSource: String = "Est.", // "Depth", "Optics", or "Est." — how distance was measured
    val approachScore: Float = 0f, // [-1, 1]: positive = approaching, negative = receding
    val isApproaching: Boolean = false,
    val isStationary: Boolean = true,
    val stationaryFrames: Int = 0 // how many consecutive frames this object has been stationary
)

enum class ObstacleCategory {
    COLLISION_RISK,   // walls, poles, people, vehicles ahead
    TRIP_HAZARD,      // low objects, curbs, steps
    STREET_DANGER,    // road, vehicles, traffic
    GENERAL           // other detected objects
}
