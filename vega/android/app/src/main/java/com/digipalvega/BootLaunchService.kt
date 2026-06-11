package com.digipalvega

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Short-lived foreground service that launches MainActivity on boot.
 *
 * Why a service instead of startActivity() directly from BootReceiver:
 * Android 10+ (API 29) and FireOS 7/8 block background startActivity() calls
 * for non-system apps — the call is silently dropped. A foreground service can
 * still launch activities because it runs in the foreground context.
 *
 * Lifecycle: starts → calls startForeground() → launches MainActivity →
 *            starts WatchdogService → stops itself.
 */
class BootLaunchService : Service() {

    companion object {
        private const val CHANNEL_ID = "digipal_boot"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()

        val notification = buildSilentNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Launch the main activity now that we have a foreground context.
        try {
            val launch = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(launch)
        } catch (e: Exception) {
            // Ignore — if this fails the WatchdogService will retry.
        }

        // Start the persistent watchdog that will restart the app on crash.
        try {
            val watchdog = Intent(this, WatchdogService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(watchdog)
            } else {
                startService(watchdog)
            }
        } catch (e: Exception) {
            // Non-fatal — watchdog will be started by WatchdogService's START_STICKY.
        }

        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Digipal Player",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "Digipal Player auto-start"
                    setShowBadge(false)
                    setSound(null, null)
                    enableLights(false)
                    enableVibration(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildSilentNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Digipal Player")
                .setContentText("Starting…")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Digipal Player")
                .setContentText("Starting…")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setPriority(Notification.PRIORITY_MIN)
                .build()
        }
    }
}
