package com.safewalker.model

enum class DangerLevel(val priority: Int) {
    NONE(0),
    LOW(1),
    MEDIUM(2),
    HIGH(3);

    val label: String
        get() = when (this) {
            NONE -> "Safe"
            LOW -> "Advisory"
            MEDIUM -> "Caution"
            HIGH -> "Danger!"
        }
}
