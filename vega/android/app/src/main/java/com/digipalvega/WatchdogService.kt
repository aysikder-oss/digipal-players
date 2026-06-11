package com.digipalvega

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Persistent foreground watchdog service that relaunches MainActivity if the
 * app process crashes or is backgrounded unexpectedly.
 *
 * Uses START_STICKY so the Android/FireOS process manager automatically
 * restarts this service if it is killed (e.g. OOM). On restart it will
 * immediately detect that MainActivity is not running and bring it back.
 *
 * Polling interval: 10 seconds. Restart delay after detection: 1 second.
 *
 * The notification is low-priority / minimal to avoid cluttering the
 * Fire TV notification tray — but it is required for foreground services
 * on Android 8+ (API 26).
 */
class WatchdogService : Service() {

    companion object {
        private const val CHANNEL_ID   = "digipal_watchdog"
        private const val NOTIF_ID     = 1002
        private const val CHECK_MS     = 10_000L
        private const val RESTART_DELAY_MS = 1_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!isAppInForeground()) {
                scheduleRestart()
            }
            handler.postDelayed(this, CHECK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        handler.postDelayed(watchdogRunnable, CHECK_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(watchdogRunnable)
        super.onDestroy()
    }

    // -------------------------------------------------------------------------

    private fun isAppInForeground(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
        val processes = am.runningAppProcesses ?: return true
        return processes.any { p ->
            p.processName == packageName &&
            p.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    private fun scheduleRestart() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_ONE_SHOT
        }

        val pendingIntent = PendingIntent.getActivity(this, 2, intent, piFlags)
        val triggerAt = System.currentTimeMillis() + RESTART_DELAY_MS

        val am = getSystemService(ALARM_SERVICE) as? AlarmManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (e: SecurityException) {
            // setExactAndAllowWhileIdle may throw if SCHEDULE_EXACT_ALARM not granted.
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Digipal Player",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "Keeps Digipal Player running"
                    setShowBadge(false)
                    setSound(null, null)
                    enableLights(false)
                    enableVibration(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Digipal Player")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Digipal Player")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setPriority(Notification.PRIORITY_MIN)
                .setOngoing(true)
                .build()
        }
    }
}
