package com.digipal.signage;

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
 * notification — the only BAL-safe path on Android 14 (API 34) / targetSdk 35
 * that requires no special permissions beyond USE_FULL_SCREEN_INTENT (normal,
 * auto-granted on install).
 *
 * Key insight: Android's NotificationManagerService only sets mIsInterruptive=true
 * (and therefore fires the fullScreenIntent) when the notification's channel has a
 * non-null sound OR a vibration pattern.  A channel with sound=null + vibration=null
 * is treated as non-interruptive → headsUpContentView=null → fullScreenIntent never
 * fires even though it is set in the Notification object.
 *
 * Channel ID is versioned (digipal_relaunch_v2) because channel settings are sticky:
 * once created, you cannot change sound/vibration on an existing channel — only
 * creating a new channel ID picks up the updated settings.
 */
public class RelaunchReceiver extends BroadcastReceiver {

    /** Internal broadcast action — not exported to other apps. */
    static final String ACTION_RELAUNCH = "com.digipal.signage.internal.RELAUNCH";

    // v2 channel: previous channel (digipal_relaunch) was created with sound=null,
    // which caused mIsInterruptive=false and suppressed the fullScreenIntent.
    // A new channel ID is the only way to pick up corrected sound/vibration settings.
    private static final String CHANNEL_ID = "digipal_relaunch_v2";
    private static final int    NOTIF_ID   = 1005;

    @Override
    public void onReceive(Context context, Intent intent) {
        String reason = intent != null ? intent.getStringExtra("relaunchReason") : null;
        Log.i("DigipalRecovery", "RelaunchReceiver.onReceive: posting full-screen notification reason=" + reason);
        postRelaunchNotification(context, reason);
    }

    /**
     * Posts a high-priority full-screen-intent notification that immediately
     * launches MainActivity.  Safe to call from any context including a
     * foreground service or a BroadcastReceiver.
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
            PendingIntent activityPi = PendingIntent.getActivity(
                    context, NOTIF_ID, launch, piFlags);

            // Cancel any previous relaunch notification first.
            // Stale notifications in the same slot can be demoted to non-interruptive
            // by the system if mIsInterruptive was already false on the old entry.
            nm.cancel(NOTIF_ID);

            Notification notification;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notification = new Notification.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setContentTitle("Digipal Player")
                        .setContentText("Restarting…")
                        // fullScreenIntent with highPriority=true is the BAL bypass.
                        .setFullScreenIntent(activityPi, true)
                        // contentIntent fires if the user taps the notification shade
                        // (fallback path on devices that suppress full-screen intents).
                        .setContentIntent(activityPi)
                        .setCategory(Notification.CATEGORY_ALARM)
                        // PRIORITY_MAX on the Notification object is respected on some
                        // older API levels and by certain OEM notification stacks.
                        .setPriority(Notification.PRIORITY_MAX)
                        .setAutoCancel(true)
                        .setOnlyAlertOnce(false)
                        // No group — group-summary logic can suppress interruptiveness
                        // for non-summary notifications that share a group key.
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
                        // DEFAULT_SOUND + DEFAULT_VIBRATE ensure mIsInterruptive=true
                        // on pre-Oreo builds where channels don't exist.
                        .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE)
                        .setAutoCancel(true)
                        .build();
            }

            nm.notify(NOTIF_ID, notification);
            Log.i("DigipalRecovery",
                    "RelaunchReceiver.postRelaunchNotification: notification posted"
                    + " channel=" + CHANNEL_ID + " notifId=" + NOTIF_ID);
        } catch (Exception e) {
            Log.w("DigipalRecovery", "RelaunchReceiver.postRelaunchNotification failed", e);
        }
    }

    private static void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return;

            // Sound + vibration are REQUIRED for Android to mark this notification as
            // interruptive (mIsInterruptive=true).  Without them:
            //   headsUpContentView=null  →  fullScreenIntent is never dispatched.
            // Use TYPE_ALARM (highest priority audio stream) so the sound fires even
            // when the device is in Do-Not-Disturb — alarm-category notifications bypass DND.
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
