package com.digipal.signage;

import android.app.ActivityManager;
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
import android.provider.Settings;
import android.util.Log;
import java.util.List;

/**
 * Receives the relaunch alarm and brings MainActivity back to the foreground.
 *
 * LAYERED STRATEGY (tried in order)
 * ----------------------------------
 * 1. AppTask.moveToFront()
 *    No permission needed. Works when the app is paused/backgrounded but not killed.
 *
 * 2. SYSTEM_ALERT_WINDOW + startActivity()
 *    SYSTEM_ALERT_WINDOW ("Display over other apps") is an explicit Android BAL
 *    exemption documented in AOSP ActivityStarter. Works even when the app is killed.
 *    Grant once per device:
 *      adb shell appops set <package> SYSTEM_ALERT_WINDOW allow
 *    Or programmatically via openOverlayPermissionSettings() bridge in MainActivity.
 *
 * 3. Full-screen-intent notification (last resort)
 *    On phones this reliably fires via mIsInterruptive=true. On many Android TV /
 *    Fire TV ROMs the FSI dispatch is suppressed even when correctly configured.
 *    Retained as a best-effort fallback.
 *
 * CHANNEL NOTES
 * -------------
 * Channel "digipal_relaunch_v2" carries sound + vibration so mIsInterruptive=true.
 * Channel settings are sticky — the old silent channel (digipal_relaunch) is abandoned.
 * PendingIntent carries creator-side BAL opt-in for API 34+ (targetSdk=35 requirement).
 */
public class RelaunchReceiver extends BroadcastReceiver {

    static final String ACTION_RELAUNCH = "com.digipal.signage.internal.RELAUNCH";

    private static final String TAG       = "DigipalRecovery";
    private static final String CHANNEL_ID = "digipal_relaunch_v2";
    private static final int    NOTIF_ID  = 1005;

    @Override
    public void onReceive(Context context, Intent intent) {
        String reason = intent != null ? intent.getStringExtra("relaunchReason") : null;
        Log.i(TAG, "RelaunchReceiver.onReceive reason=" + reason);
        postRelaunchNotification(context, reason);
    }

    static void postRelaunchNotification(Context context, String reason) {
        // ── Step 1: AppTask.moveToFront() ──────────────────────────────────────
        // Brings an existing (backgrounded-not-killed) task to front without
        // needing any special permission.
        try {
            ActivityManager am = (ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.AppTask> tasks = am.getAppTasks();
                if (tasks != null && !tasks.isEmpty()) {
                    tasks.get(0).moveToFront();
                    Log.i(TAG, "RelaunchReceiver: relaunch via AppTask.moveToFront");
                    return;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "RelaunchReceiver: moveToFront failed: " + e);
        }

        // ── Step 2: SYSTEM_ALERT_WINDOW direct startActivity() ─────────────────
        // SYSTEM_ALERT_WINDOW is an explicit BAL exemption in AOSP ActivityStarter.
        // Works even when the app process is dead.  Grant via adb appops or the
        // openOverlayPermissionSettings() bridge.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Settings.canDrawOverlays(context)) {
            try {
                Intent launch = new Intent(context, MainActivity.class);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                if (reason != null) launch.putExtra("relaunchReason", reason);
                context.startActivity(launch);
                Log.i(TAG, "RelaunchReceiver: relaunch via SYSTEM_ALERT_WINDOW"
                        + " overlayPermission=true startActivityViaOverlay=true");
                return;
            } catch (Exception e) {
                Log.w(TAG, "RelaunchReceiver: overlay startActivity failed: " + e);
            }
        } else {
            Log.w(TAG, "RelaunchReceiver: SYSTEM_ALERT_WINDOW not granted"
                    + " — falling back to FSI notification."
                    + " Grant with: adb shell appops set "
                    + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        }

        // ── Step 3: Full-screen-intent notification (last resort) ───────────────
        // Correctly configured: mIsInterruptive=true, alarm sound/vibration,
        // creator-side BAL opt-in on API 34+.  Reliable on phones; suppressed
        // by some Android TV / Fire TV ROMs.
        try {
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                Log.w(TAG, "RelaunchReceiver: NotificationManager null");
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

            // Creator-side BAL opt-in required on API 34+ / targetSdk=35.
            // Without this the OS silently logs BAL_BLOCK and drops the launch.
            PendingIntent activityPi;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions opts = ActivityOptions.makeBasic();
                opts.setPendingIntentCreatorBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                activityPi = PendingIntent.getActivity(
                        context, NOTIF_ID, launch, piFlags, opts.toBundle());
            } else {
                activityPi = PendingIntent.getActivity(
                        context, NOTIF_ID, launch, piFlags);
            }

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
            Log.i(TAG, "RelaunchReceiver: FSI notification posted"
                    + " channel=" + CHANNEL_ID
                    + " creatorBalOptIn=" + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE));
        } catch (Exception e) {
            Log.w(TAG, "RelaunchReceiver.postRelaunchNotification failed", e);
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
                    CHANNEL_ID, "Player Relaunch", NotificationManager.IMPORTANCE_HIGH);
            ch.setShowBadge(false);
            ch.setSound(alarmSound, audioAttrs);
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0L, 200L});
            ch.enableLights(false);
            ch.setDescription("Restarts the player after a crash or update");
            nm.createNotificationChannel(ch);
            Log.i(TAG, "RelaunchReceiver: created channel " + CHANNEL_ID);
        }
    }
}
