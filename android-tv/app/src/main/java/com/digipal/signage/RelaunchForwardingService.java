package com.digipal.signage;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Trampoline foreground service that launches MainActivity from the background.
 *
 * WHY THIS EXISTS
 * ---------------
 * On Android 10+ (API 29+) there are documented exceptions that allow an app to
 * start a foreground activity from the background.  One of those exceptions is:
 *   "The app has a running foreground service."
 * (https://developer.android.com/guide/components/activities/background-starts)
 *
 * The full-screen-intent notification path was tried first.  On Android TV /
 * Fire TV the notification stack suppresses full-screen intent dispatch even
 * when mIsInterruptive=true — the mechanism is phone-centric and not reliable
 * on TV hardware.
 *
 * HOW IT WORKS
 * ------------
 * 1. RelaunchReceiver.onReceive() calls startForegroundService(this service).
 *    startForegroundService() from a BroadcastReceiver is always allowed.
 * 2. onStartCommand() immediately calls startForeground() (satisfying the 5s
 *    ANR window), then calls startActivity(MainActivity).
 *    Because a foreground service is running, the BAL exemption applies.
 * 3. The service stops itself right away — it is intentionally short-lived.
 *
 * FALLBACK
 * --------
 * If startActivity() throws (should not happen under the BAL exemption, but
 * guarded defensively), postRelaunchNotification() is called as a last-resort
 * on the chance the device has a working FSI stack.
 */
public class RelaunchForwardingService extends Service {

    private static final String TAG       = "DigipalRecovery";
    private static final String CHANNEL_ID = "digipal_relaunch_fwd";
    private static final int    NOTIF_ID  = 1006;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Must call startForeground() within 5 seconds of service start.
        startForeground(NOTIF_ID, buildNotification());

        String reason = intent != null ? intent.getStringExtra("relaunchReason") : null;
        Log.i(TAG, "RelaunchForwardingService.onStartCommand: launching MainActivity"
                + " reason=" + reason);

        try {
            Intent launch = new Intent(this, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (reason != null) launch.putExtra("relaunchReason", reason);
            startActivity(launch);
            Log.i(TAG, "RelaunchForwardingService: startActivity dispatched");
        } catch (Exception e) {
            // Defensive fallback — should not be reached under the foreground-service BAL exemption.
            Log.w(TAG, "RelaunchForwardingService: startActivity failed, posting notification fallback", e);
            RelaunchReceiver.postRelaunchNotification(this, reason);
        }

        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private Notification buildNotification() {
        ensureChannel();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Digipal Player")
                    .setContentText("Restarting…")
                    .build();
        } else {
            return new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Digipal Player")
                    .setContentText("Restarting…")
                    .setPriority(Notification.PRIORITY_MIN)
                    .build();
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "Player Relaunch", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
        }
    }
}
