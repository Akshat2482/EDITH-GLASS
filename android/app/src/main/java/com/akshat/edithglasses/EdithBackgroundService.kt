package com.akshat.edithglasses

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps EDITH's speech/BLE process alive while the app is in the background.
 * The actual speech/BLE engine remains owned by EdithViewModel; this
 * foreground service prevents the Android process from being treated as an
 * ordinary background app while the user is using the hands-free mode.
 */
class EdithBackgroundService : Service() {

    companion object {
        const val CHANNEL_ID = "edith_background"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.edith_icon)
            .setContentTitle("EDITH is listening")
            .setContentText("Speech transcription and glasses connection are active")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Keep the service/process alive if Android reclaims it.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "EDITH background listening",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps EDITH speech transcription and BLE active in the background"
                    setShowBadge(false)
                }
            )
        }
    }
}
