package com.safewalker.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.safewalker.R
import com.safewalker.model.DangerLevel
import java.util.Locale

/**
 * Manages alerts: text-to-speech warnings, vibration patterns,
 * and notification updates based on danger level.
 */
class AlertManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val vibrator: Vibrator
    private val notificationManager: NotificationManager
    private var lastAlertTime = 0L
    private var lastDangerLevel = DangerLevel.NONE

    companion object {
        const val DETECTION_CHANNEL_ID = "obstacle_detection"
        const val ALERT_CHANNEL_ID = "danger_alerts"
        const val DETECTION_NOTIFICATION_ID = 1
        const val ALERT_NOTIFICATION_ID = 2
        private const val TAG = "AlertManager"

        // Minimum interval between alerts (ms) per danger level
        private val ALERT_COOLDOWNS = mapOf(
            DangerLevel.HIGH to 2000L,
            DangerLevel.MEDIUM to 4000L,
            DangerLevel.LOW to 8000L
        )

        // Vibration patterns (ms): [delay, vibrate, pause, vibrate, ...]
        private val VIBRATION_HIGH = longArrayOf(0, 300, 100, 300, 100, 500)
        private val VIBRATION_MEDIUM = longArrayOf(0, 200, 200, 200)
        private val VIBRATION_LOW = longArrayOf(0, 100)
    }

    init {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannels()
        initTts()
    }

    private fun createNotificationChannels() {
        val detectionChannel = NotificationChannel(
            DETECTION_CHANNEL_ID,
            context.getString(R.string.detection_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.detection_channel_description)
        }

        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            context.getString(R.string.alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.alert_channel_description)
            enableVibration(true)
        }

        notificationManager.createNotificationChannel(detectionChannel)
        notificationManager.createNotificationChannel(alertChannel)
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                isTtsReady = true
            } else {
                Log.w(TAG, "TTS initialization failed")
            }
        }
    }

    fun alert(dangerLevel: DangerLevel, message: String) {
        if (dangerLevel == DangerLevel.NONE) {
            cancelAlert()
            return
        }

        val now = System.currentTimeMillis()
        val cooldown = ALERT_COOLDOWNS[dangerLevel] ?: 4000L

        // Immediate alert if danger escalated, otherwise respect cooldown
        val shouldAlert = dangerLevel.priority > lastDangerLevel.priority ||
                (now - lastAlertTime) > cooldown

        if (!shouldAlert) return

        lastAlertTime = now
        lastDangerLevel = dangerLevel

        vibrate(dangerLevel)
        speak(message)
        showAlertNotification(dangerLevel, message)
    }

    private fun vibrate(dangerLevel: DangerLevel) {
        val pattern = when (dangerLevel) {
            DangerLevel.HIGH -> VIBRATION_HIGH
            DangerLevel.MEDIUM -> VIBRATION_MEDIUM
            DangerLevel.LOW -> VIBRATION_LOW
            DangerLevel.NONE -> return
        }
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun speak(message: String) {
        if (isTtsReady && message.isNotEmpty()) {
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "alert_${System.currentTimeMillis()}")
        }
    }

    private fun showAlertNotification(dangerLevel: DangerLevel, message: String) {
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("SafeWalker: ${dangerLevel.label}")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun cancelAlert() {
        // Do NOT reset lastDangerLevel here — if detection flickers through
        // NONE for a frame or two, we still want the cooldown to apply against
        // the previous danger level so we don't spam alerts.
        notificationManager.cancel(ALERT_NOTIFICATION_ID)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        vibrator.cancel()
        notificationManager.cancel(ALERT_NOTIFICATION_ID)
    }
}
