package com.safewalker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.safewalker.model.DangerLevel
import com.safewalker.model.DetectedObstacle

/**
 * Custom View that draws bounding boxes, labels, and distance estimates
 * over the camera preview for each detected obstacle.
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var obstacles: List<DetectedObstacle> = emptyList()
    private var sourceImageWidth: Int = 1
    private var sourceImageHeight: Int = 1

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
        isFakeBoldText = true
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    private val distancePaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    fun updateDetections(
        detectedObstacles: List<DetectedObstacle>,
        imageWidth: Int,
        imageHeight: Int
    ) {
        obstacles = detectedObstacles
        sourceImageWidth = imageWidth
        sourceImageHeight = imageHeight
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (obstacles.isEmpty()) return

        val scaleX = width.toFloat() / sourceImageWidth
        val scaleY = height.toFloat() / sourceImageHeight

        for (obstacle in obstacles) {
            val color = dangerColor(obstacle.dangerLevel)
            boxPaint.color = color

            // Scale bounding box to view coordinates
            val scaledBox = RectF(
                obstacle.boundingBox.left * scaleX,
                obstacle.boundingBox.top * scaleY,
                obstacle.boundingBox.right * scaleX,
                obstacle.boundingBox.bottom * scaleY
            )

            // Draw bounding box
            canvas.drawRect(scaledBox, boxPaint)

            // Draw semi-transparent fill for high danger
            if (obstacle.dangerLevel == DangerLevel.HIGH) {
                fillPaint.color = Color.argb(40, Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawRect(scaledBox, fillPaint)
            }

            // Draw label background
            val label = obstacle.label
            val distance = "~${String.format("%.1f", obstacle.estimatedDistance)}m"
            val dangerLabel = obstacle.dangerLevel.label

            val labelText = "$label  $distance"
            val textWidth = textPaint.measureText(labelText)
            val textHeight = textPaint.textSize

            // Background for label
            fillPaint.color = Color.argb(180, 0, 0, 0)
            val labelBgTop = (scaledBox.top - textHeight - 16).coerceAtLeast(0f)
            canvas.drawRoundRect(
                scaledBox.left,
                labelBgTop,
                (scaledBox.left + textWidth + 16).coerceAtMost(width.toFloat()),
                scaledBox.top,
                8f, 8f,
                fillPaint
            )

            // Draw label text
            textPaint.color = Color.WHITE
            canvas.drawText(
                label,
                scaledBox.left + 8,
                scaledBox.top - 8,
                textPaint
            )

            // Draw distance on the right side of the label
            val labelWidth = textPaint.measureText("$label  ")
            distancePaint.color = Color.rgb(200, 230, 255)
            canvas.drawText(
                distance,
                scaledBox.left + 8 + labelWidth,
                scaledBox.top - 8,
                distancePaint
            )

            // Draw danger level + motion badge at bottom of box
            if (obstacle.dangerLevel != DangerLevel.NONE || obstacle.isApproaching) {
                val motionIndicator = when {
                    obstacle.isApproaching -> " \u25B2"  // up arrow = approaching
                    obstacle.isStationary && obstacle.stationaryFrames > 5 -> " \u25CF"  // dot = stationary
                    else -> ""
                }
                val badgeText = "$dangerLabel$motionIndicator"
                val badgeWidth = distancePaint.measureText(badgeText)
                fillPaint.color = Color.argb(200, Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawRoundRect(
                    scaledBox.left,
                    scaledBox.bottom,
                    scaledBox.left + badgeWidth + 16,
                    scaledBox.bottom + textHeight + 8,
                    8f, 8f,
                    fillPaint
                )
                distancePaint.color = Color.WHITE
                canvas.drawText(
                    badgeText,
                    scaledBox.left + 8,
                    scaledBox.bottom + textHeight,
                    distancePaint
                )
            }
        }
    }

    private fun dangerColor(level: DangerLevel): Int = when (level) {
        DangerLevel.HIGH -> Color.rgb(244, 67, 54)    // red
        DangerLevel.MEDIUM -> Color.rgb(255, 152, 0)  // orange
        DangerLevel.LOW -> Color.rgb(255, 235, 59)    // yellow
        DangerLevel.NONE -> Color.rgb(76, 175, 80)    // green
    }
}
