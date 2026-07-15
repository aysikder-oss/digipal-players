package com.nexuscast.player;

  import android.app.AlarmManager;
  import android.app.Notification;
  import android.app.NotificationChannel;
  import android.app.NotificationManager;
  import android.app.PendingIntent;
  import android.app.Service;
  import android.content.Intent;
  import android.os.Build;
  import android.content.pm.ServiceInfo;
  import android.os.IBinder;
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
          scheduleLaunch();
          // WatchdogService is started in MainActivity.onCreate() after the
          // Activity is visible. Android 15 blocks mediaPlayback FGS from
          // the BOOT_COMPLETED chain, so we must not start it here.
          stopSelf();
          return START_NOT_STICKY;
      }

      private void scheduleLaunch() {
          try {
              Intent launch = new Intent(this, MainActivity.class);
              launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                      | Intent.FLAG_ACTIVITY_CLEAR_TOP
                      | Intent.FLAG_ACTIVITY_SINGLE_TOP);

              int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                      | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                         ? PendingIntent.FLAG_IMMUTABLE : 0);

              PendingIntent pi = PendingIntent.getActivity(this, 1001, launch, piFlags);
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
  