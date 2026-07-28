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

            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            launch.putExtra("relaunchReason", reason);

            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                       ? PendingIntent.FLAG_IMMUTABLE : 0);

            // Android 14 (API 34 / UPSIDE_DOWN_CAKE) Background Activity Launch (BAL):
            // AlarmManager fires the PendingIntent as the SYSTEM, so the *creator-side*
            // option must be set at PendingIntent creation time.
            // setPendingIntentCreatorBackgroundActivityStartMode was added in API 34.
            // The sender-side method (setPendingIntentBackgroundActivityStartMode, API 34)
            // is intentionally NOT used — it has no effect when the system is the sender.
            android.os.Bundle activityOpts = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.app.ActivityOptions ao = android.app.ActivityOptions.makeBasic();
                ao.setPendingIntentCreatorBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                activityOpts = ao.toBundle();
            }
            PendingIntent pi = PendingIntent.getActivity(
                    context, REQ_PACKAGE_UPDATE, launch, piFlags, activityOpts);
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                Log.w("DigipalRecovery", "BootLaunchService.schedulePlayerLaunch: AlarmManager null");
                return;
            }
            long earliest = SystemClock.elapsedRealtime() + delayMs;
            am.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, earliest, 5_000L, pi);
            Log.i("DigipalRecovery", "BootLaunchService.schedulePlayerLaunch: alarm set"
                    + " requestCode=" + REQ_PACKAGE_UPDATE + " at+" + delayMs + "ms");
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
          try {
              Intent launch = new Intent(this, MainActivity.class);
              launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                      | Intent.FLAG_ACTIVITY_CLEAR_TOP
                      | Intent.FLAG_ACTIVITY_SINGLE_TOP);
              if (reason != null) launch.putExtra("relaunchReason", reason);

              int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                      | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                         ? PendingIntent.FLAG_IMMUTABLE : 0);

              // Android 14 (API 34 / UPSIDE_DOWN_CAKE) BAL — same reasoning as schedulePlayerLaunch.
              // Must use setPendingIntentCreatorBackgroundActivityStartMode (creator side)
              // because AlarmManager fires the PendingIntent as the system, not the app.
              android.os.Bundle bootActivityOpts = null;
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                  android.app.ActivityOptions ao = android.app.ActivityOptions.makeBasic();
                  ao.setPendingIntentCreatorBackgroundActivityStartMode(
                          android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                  bootActivityOpts = ao.toBundle();
              }
              PendingIntent pi = PendingIntent.getActivity(this, 1001, launch, piFlags, bootActivityOpts);
              AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
              if (am == null) return;

              // setWindow() fires within [500 ms, 5 s] - no special permission needed.
              // Gives the window manager time to finish initialising after boot.
              long earliest = SystemClock.elapsedRealtime() + 500;
              am.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, earliest, 5_000L, pi);
          } catch (Exception ignored) {}
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
  