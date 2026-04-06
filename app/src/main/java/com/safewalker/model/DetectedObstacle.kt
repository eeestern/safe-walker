package com.safewalker.model

import android.graphics.RectF

data class DetectedObstacle(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF,
    val category: ObstacleCategory,
    val dangerLevel: DangerLevel,
    val estimatedDistance: Float // rough distance estimate based on bounding box size
)

enum class ObstacleCategory {
    COLLISION_RISK,   // walls, poles, people, vehicles ahead
    TRIP_HAZARD,      // low objects, curbs, steps
    STREET_DANGER,    // road, vehicles, traffic
    GENERAL           // other detected objects
}
