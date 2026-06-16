package com.digipal.player;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.List;

/**
 * Persistent foreground watchdog service that relaunches MainActivity if the
 * app process crashes or is backgrounded unexpectedly.
 *
 * Uses START_STICKY so the Android/FireOS process manager automatically
 * restarts this service if it is killed (e.g. OOM). On restart it will
 * immediately detect that MainActivity is not running and bring it back.
 *
 * Polling interval : 10 seconds.
 * Restart delay after detection : 1 second (via BootLaunchService so it
 *   works even if the device is in Doze mode and on Android 12+).
 *
 * Standing crash-recovery alarm: a repeating 60-second AlarmManager alarm
 *   targets BootLaunchService. If the entire process is OOM-killed before
 *   START_STICKY can restart this service, the alarm fires and BootLaunchService
 *   reopens the app automatically. The alarm is cancelled when this service
 *   is explicitly destroyed (graceful shutdown).
 *
 * The notification is low-priority / minimal to avoid cluttering the
 * Fire TV notification tray, but it is required for foreground services
 * on Android 8+ (API 26).
 */
public class WatchdogService extends Service {

    private static final String CHANNEL_ID            = "digipal_player_watchdog";
    private static final int    NOTIF_ID              = 1002;
    private static final long   CHECK_MS              = 10_000L;
    private static final long   RESTART_DELAY_MS      = 1_000L;
    private static final long   CRASH_ALARM_INTERVAL  = 60_000L;
    // Request code must differ from the one-shot alarm in scheduleRestart().
    private static final int    CRASH_ALARM_REQUEST   = 99;

    private Handler  handler;
    private Runnable watchdogRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());

        handler = new Handler(Looper.getMainLooper());
        watchdogRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isAppInForeground()) {
                    scheduleRestart();
                }
                handler.postDelayed(this, CHECK_MS);
            }
        };
        handler.postDelayed(watchdogRunnable, CHECK_MS);

        // Register a standing repeating alarm that targets BootLaunchService.
        // If the entire process is OOM-killed, this alarm continues to fire
        // every 60 s and re-opens the app even before START_STICKY reschedules
        // this service.
        scheduleCrashRecoveryAlarm();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (handler != null && watchdogRunnable != null) {
            handler.removeCallbacks(watchdogRunnable);
        }
        // Cancel the standing alarm on graceful shutdown (e.g. user disabled
        // watchdog from settings). It is NOT cancelled on OOM kill — that is
        // exactly when we need it to keep firing.
        cancelCrashRecoveryAlarm();
        super.onDestroy();
    }

    // -------------------------------------------------------------------------

    /**
     * Returns true when the app process is actively in the foreground in any
     * meaningful sense (visible, loading, transitioning between activities).
     *
     * Uses <= IMPORTANCE_FOREGROUND_SERVICE rather than == IMPORTANCE_FOREGROUND
     * to avoid false-positive restarts when the activity is momentarily in a
     * transitioning or service-backed foreground state.
     */
    private boolean isAppInForeground() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return true;
        List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
        if (processes == null) return true;
        for (ActivityManager.RunningAppProcessInfo p : processes) {
            if (p.processName.equals(getPackageName()) &&
                p.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Schedule a one-shot relaunch via BootLaunchService.
     *
     * Why BootLaunchService instead of PendingIntent.getActivity():
     * Android 12+ (API 31) blocks AlarmManager from starting Activities
     * directly when the app is not in the foreground. BootLaunchService is a
     * foreground service — it calls startForeground() first, then launches
     * MainActivity from a foreground context, which is legal on all versions.
     */
    private void scheduleRestart() {
        Intent serviceIntent = new Intent(this, BootLaunchService.class);

        int piFlags = PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getService(this, 20, serviceIntent, piFlags);
        long triggerAt = System.currentTimeMillis() + RESTART_DELAY_MS;

        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            }
        } catch (SecurityException e) {
            // setExactAndAllowWhileIdle may throw if SCHEDULE_EXACT_ALARM not granted.
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        }
    }

    /**
     * Register a repeating AlarmManager alarm targeting BootLaunchService.
     * This is the last line of defense against OOM kills — even if START_STICKY
     * hasn't restarted WatchdogService yet, the alarm fires and reopens the app.
     */
    private void scheduleCrashRecoveryAlarm() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) return;

        PendingIntent pi = buildCrashAlarmIntent();
        long firstFire = System.currentTimeMillis() + CRASH_ALARM_INTERVAL;
        am.setRepeating(AlarmManager.RTC_WAKEUP, firstFire, CRASH_ALARM_INTERVAL, pi);
    }

    private void cancelCrashRecoveryAlarm() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) return;
        am.cancel(buildCrashAlarmIntent());
    }

    private PendingIntent buildCrashAlarmIntent() {
        Intent serviceIntent = new Intent(this, BootLaunchService.class);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, CRASH_ALARM_REQUEST, serviceIntent, piFlags);
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Digipal Player",
                    NotificationManager.IMPORTANCE_MIN
                );
                ch.setDescription("Keeps Digipal Player running");
                ch.setShowBadge(false);
                ch.setSound(null, null);
                ch.enableLights(false);
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Digipal Player")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
        } else {
            //noinspection deprecation
            return new Notification.Builder(this)
                .setContentTitle("Digipal Player")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setPriority(Notification.PRIORITY_MIN)
                .setOngoing(true)
                .build();
        }
    }
}
