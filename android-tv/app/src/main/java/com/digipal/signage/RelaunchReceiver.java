package com.digipal.signage;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

/**
 * Receives the relaunch alarm and starts MainActivity via a full-screen-intent
 * notification.
 *
 * MECHANISM
 * ---------
 * Full-screen intents are an Android-sanctioned background-activity-launch (BAL)
 * path that works on Android TV / Fire TV and does not require SCHEDULE_EXACT_ALARM,
 * SYSTEM_ALERT_WINDOW, or device-owner privileges.
 *
 * CRITICAL — targetSdk=35 PendingIntent BAL opt-in
 * -------------------------------------------------
 * On API 34+ (Android 14+) with targetSdk=35 the system requires an explicit
 * creator-side opt-in for any PendingIntent that will be used to start an
 * activity from the background:
 *
 *   ActivityOptions.setPendingIntentCreatorBackgroundActivityStartMode(
 *           ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
 *
 * Without this, the system logs BAL_BLOCK internally and silently drops the
 * launch — no exception is thrown, so the caller incorrectly thinks it succeeded.
 *
 * CHANNEL VERSIONING
 * ------------------
 * Channel settings are sticky once created; the old silent channel is abandoned.
 * digipal_relaunch_v2 carries sound + vibration so mIsInterruptive=true, which is
 * required for the system to actually dispatch the fullScreenIntent.
 */
public class RelaunchReceiver extends BroadcastReceiver {

    /** Internal broadcast action — not exported to other apps. */
    static final String ACTION_RELAUNCH = "com.digipal.signage.internal.RELAUNCH";

    private static final String CHANNEL_ID = "digipal_relaunch_v2";
    private static final int    NOTIF_ID   = 1005;

    @Override
    public void onReceive(Context context, Intent intent) {
        String reason = intent != null ? intent.getStringExtra("relaunchReason") : null;
        Log.i("DigipalRecovery", "RelaunchReceiver.onReceive: posting full-screen notification reason=" + reason);
        postRelaunchNotification(context, reason);
    }

    /**
     * Posts a high-priority full-screen-intent notification that starts MainActivity.
     * Safe to call from any context (BroadcastReceiver, foreground service, etc.).
     */
    static void postRelaunchNotification(Context context, String reason) {
        try {
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                Log.w("DigipalRecovery", "RelaunchReceiver: NotificationManager null");
                return;
            }

            ensureChannel(nm);

            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (reason != null) launch.putExtra("relaunchReason", reason);

            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                       ? PendingIntent.FLAG_IMMUTABLE : 0);

            // On API 34+ / targetSdk=35 every PendingIntent used for a background
            // activity start requires an explicit creator-side BAL opt-in.
            // Without this the system silently logs BAL_BLOCK and drops the launch —
            // no exception is thrown, so callers falsely believe the launch succeeded.
            PendingIntent activityPi;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34
                ActivityOptions opts = ActivityOptions.makeBasic();
                opts.setPendingIntentCreatorBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                activityPi = PendingIntent.getActivity(
                        context, NOTIF_ID, launch, piFlags, opts.toBundle());
            } else {
                activityPi = PendingIntent.getActivity(
                        context, NOTIF_ID, launch, piFlags);
            }

            // Cancel any stale relaunch notification first to avoid slot-suppression.
            nm.cancel(NOTIF_ID);

            Notification notification;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notification = new Notification.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setContentTitle("Digipal Player")
                        .setContentText("Restarting…")
                        .setFullScreenIntent(activityPi, true)
                        .setContentIntent(activityPi)
                        .setCategory(Notification.CATEGORY_ALARM)
                        .setPriority(Notification.PRIORITY_MAX)
                        .setAutoCancel(true)
                        .setOnlyAlertOnce(false)
                        .build();
            } else {
                notification = new Notification.Builder(context)
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setContentTitle("Digipal Player")
                        .setContentText("Restarting…")
                        .setFullScreenIntent(activityPi, true)
                        .setContentIntent(activityPi)
                        .setCategory(Notification.CATEGORY_ALARM)
                        .setPriority(Notification.PRIORITY_MAX)
                        .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE)
                        .setAutoCancel(true)
                        .build();
            }

            nm.notify(NOTIF_ID, notification);
            Log.i("DigipalRecovery",
                    "RelaunchReceiver.postRelaunchNotification: notification posted"
                    + " channel=" + CHANNEL_ID + " notifId=" + NOTIF_ID
                    + " creatorBalOptIn=" + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE));
        } catch (Exception e) {
            Log.w("DigipalRecovery", "RelaunchReceiver.postRelaunchNotification failed", e);
        }
    }

    private static void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return;

            Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmSound == null) {
                alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }

            AudioAttributes audioAttrs = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build();

            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Player Relaunch",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setShowBadge(false);
            ch.setSound(alarmSound, audioAttrs);
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0L, 200L});
            ch.enableLights(false);
            ch.setDescription("Restarts the player after a crash or update");
            nm.createNotificationChannel(ch);
            Log.i("DigipalRecovery", "RelaunchReceiver: created channel " + CHANNEL_ID);
        }
    }
}
