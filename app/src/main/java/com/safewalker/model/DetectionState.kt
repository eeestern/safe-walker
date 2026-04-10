package com.safewalker.model

data class DetectionState(
    val isRunning: Boolean = false,
    val currentDangerLevel: DangerLevel = DangerLevel.NONE,
    val obstacles: List<DetectedObstacle> = emptyList(),
    val lastWarningMessage: String = "",
    val framesProcessed: Long = 0
)
