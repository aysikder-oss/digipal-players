package com.digipal.signage;

  import android.app.AlarmManager;
  import android.app.Notification;
  import android.app.NotificationChannel;
  import android.app.NotificationManager;
  import android.app.PendingIntent;
  import android.app.Service;
  import android.content.Context;
import android.content.Intent;
  import android.os.Build;
  import android.content.pm.ServiceInfo;
  import android.os.IBinder;
import android.util.Log;
  import android.os.SystemClock;

  /**
   * Short-lived foreground service that opens MainActivity on boot.
   *
   * Why AlarmManager + PendingIntent instead of startActivity()?
   *   Android 12 (API 31) REMOVED the foreground-service exemption from
   *   background activity launch restrictions. Calling startActivity() from
   *   a service silently fails on API 31+ regardless of foreground state.
   *   PendingIntents fired by AlarmManager are dispatched by the SYSTEM,
   *   bypassing background-launch restrictions on ALL API levels without
   *   any special permissions. setWindow() (API 19+) needs no
   *   SCHEDULE_EXACT_ALARM permission.
   */
  public class BootLaunchService extends Service {
      private static final String CHANNEL_ID = "digipal_boot";
      private static final int NOTIF_ID = 1001;

      /**
     * Request code for WebView-recovery / package-update relaunches.
     * Distinct from boot (1001) and watchdog restart (2) so alarms do not clobber each other.
     */
    private static final int REQ_PACKAGE_UPDATE = 1003;

    /**
     * Static helper — schedule a MainActivity relaunch via AlarmManager after delayMs.
     * Safe to call from any BroadcastReceiver or Service (no Context lifecycle concerns).
     * Uses ELAPSED_REALTIME_WAKEUP + setWindow() — no SCHEDULE_EXACT_ALARM permission needed.
     *
     * @param context   Application or receiver context
     * @param reason    Free-form label logged by MainActivity on launch (e.g. "webview_package_replaced")
     * @param delayMs   Minimum milliseconds before the alarm fires (fires within delayMs + 5000)
     */
    public static void schedulePlayerLaunch(Context context, String reason, long delayMs) {
        try {
            Log.i("DigipalRecovery", "BootLaunchService.schedulePlayerLaunch:"
                    + " reason=" + reason + " delayMs=" + delayMs);

            // Route through RelaunchReceiver which posts a full-screen-intent notification.
            // Full-screen intents are an Android-sanctioned BAL bypass (USE_FULL_SCREEN_INTENT,
            // auto-granted on install) — no SCHEDULE_EXACT_ALARM or SYSTEM_ALERT_WINDOW needed.
            // setAlarmClock() was removed: it requires SCHEDULE_EXACT_ALARM (not auto-granted
            // on Android 12L+) and its BAL exemption does not fire reliably when the permission
            // is granted via appops rather than Settings > Alarms & reminders.
            Intent relaunchIntent = new Intent(context, RelaunchReceiver.class);
            relaunchIntent.setAction(RelaunchReceiver.ACTION_RELAUNCH);
            if (reason != null) relaunchIntent.putExtra("relaunchReason", reason);

            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                       ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pi = PendingIntent.getBroadcast(context, REQ_PACKAGE_UPDATE,
                    relaunchIntent, piFlags);

            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                Log.w("DigipalRecovery", "BootLaunchService.schedulePlayerLaunch: AlarmManager null");
                return;
            }

            // setWindow() — inexact (±5 s), no exact-alarm permission required.
            long trigger = SystemClock.elapsedRealtime() + delayMs;
            am.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, 5000L, pi);
            Log.i("DigipalRecovery", "BootLaunchService.schedulePlayerLaunch: alarm set"
                    + " requestCode=" + REQ_PACKAGE_UPDATE + " at+" + delayMs + "ms (setWindow→notification)");
        } catch (Exception e) {
            Log.w("DigipalRecovery", "schedulePlayerLaunch failed", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
          ensureChannel();
          // API 34+ requires a declared foreground-service type.
          // shortService is correct for a short-lived boot-launch helper.
          try {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                  startForeground(NOTIF_ID, buildNotif(),
                      ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
              } else {
                  startForeground(NOTIF_ID, buildNotif());
              }
          } catch (Throwable t) {
              stopSelf();
              return START_NOT_STICKY;
          }
          // Forward relaunchReason from whoever started this service (WatchdogService
          // deadman alarm, PackageUpdateReceiver, etc.) into the MainActivity launch intent.
          String reason = (intent != null) ? intent.getStringExtra("relaunchReason") : null;
          Log.i("DigipalRecovery", "BootLaunchService fired, reason=" + (reason != null ? reason : "boot"));
          scheduleLaunch(reason);
          // WatchdogService is started in MainActivity.onCreate() after the
          // Activity is visible. Android 15 blocks mediaPlayback FGS from
          // the BOOT_COMPLETED chain, so we must not start it here.
          stopSelf();
          return START_NOT_STICKY;
      }

      private void scheduleLaunch() {
          scheduleLaunch(null);
      }

      private void scheduleLaunch(String reason) {
          // We are already running as a foreground service — post the
          // full-screen-intent notification directly (no intermediate alarm needed).
          RelaunchReceiver.postRelaunchNotification(this, reason);
      }

            @Override public IBinder onBind(Intent i) { return null; }

      private void ensureChannel() {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
              if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                  NotificationChannel ch = new NotificationChannel(
                          CHANNEL_ID, "Digipal Player", NotificationManager.IMPORTANCE_MIN);
                  ch.setShowBadge(false); ch.setSound(null, null);
                  ch.enableLights(false); ch.enableVibration(false);
                  nm.createNotificationChannel(ch);
              }
          }
      }

      private Notification buildNotif() {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
              return new Notification.Builder(this, CHANNEL_ID)
                      .setContentTitle("Digipal Player").setContentText("Starting...")
                      .setSmallIcon(android.R.drawable.ic_media_play).build();
          return new Notification.Builder(this)
                  .setContentTitle("Digipal Player").setContentText("Starting...")
                  .setSmallIcon(android.R.drawable.ic_media_play)
                  .setPriority(Notification.PRIORITY_MIN).build();
      }
  }
  