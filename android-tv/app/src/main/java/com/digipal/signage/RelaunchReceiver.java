package com.digipal.signage;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Receives the relaunch alarm and starts MainActivity via a full-screen-intent
 * notification — the only BAL-safe path on Android 14 (API 34) / targetSdk 35
 * that requires no special permissions (no SCHEDULE_EXACT_ALARM, no
 * SYSTEM_ALERT_WINDOW).
 *
 * Full-screen intents are an officially-sanctioned Android background-activity
 * launch bypass.  The USE_FULL_SCREEN_INTENT normal permission (API 29+) is
 * declared in the manifest and is auto-granted on install.
 *
 * On Android TV / Fire TV the notification is not visually displayed (status bar
 * is hidden during playback), but the system still dispatches the full-screen
 * intent and launches MainActivity immediately.
 */
public class RelaunchReceiver extends BroadcastReceiver {

    /** Internal broadcast action — not exported to other apps. */
    static final String ACTION_RELAUNCH = "com.digipal.signage.internal.RELAUNCH";

    private static final String CHANNEL_ID  = "digipal_relaunch";
    private static final int    NOTIF_ID    = 1004;

    @Override
    public void onReceive(Context context, Intent intent) {
        String reason = intent != null ? intent.getStringExtra("relaunchReason") : null;
        Log.i("DigipalRecovery", "RelaunchReceiver.onReceive: posting full-screen notification reason=" + reason);
        postRelaunchNotification(context, reason);
    }

    /**
     * Posts a high-priority full-screen-intent notification that immediately
     * launches MainActivity.  Safe to call from any context including a foreground
     * service or a BroadcastReceiver.
     */
    static void postRelaunchNotification(Context context, String reason) {
        try {
            ensureChannel(context);

            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (reason != null) launch.putExtra("relaunchReason", reason);

            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                       ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent fullScreenPi = PendingIntent.getActivity(context, NOTIF_ID, launch, piFlags);

            Notification notification;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notification = new Notification.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("Digipal Player")
                        .setContentText("Restarting…")
                        .setFullScreenIntent(fullScreenPi, true)
                        .setCategory(Notification.CATEGORY_ALARM)
                        .setAutoCancel(true)
                        .build();
            } else {
                notification = new Notification.Builder(context)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("Digipal Player")
                        .setContentText("Restarting…")
                        .setFullScreenIntent(fullScreenPi, true)
                        .setCategory(Notification.CATEGORY_ALARM)
                        .setPriority(Notification.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .build();
            }

            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, notification);

            Log.i("DigipalRecovery", "RelaunchReceiver.postRelaunchNotification: notification posted");
        } catch (Exception e) {
            Log.w("DigipalRecovery", "RelaunchReceiver.postRelaunchNotification failed", e);
        }
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Player Relaunch",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableLights(false);
            ch.enableVibration(false);
            ch.setDescription("Used to restart the player after a crash or update");
            nm.createNotificationChannel(ch);
        }
    }
}
