package com.digipal.signage;

      import android.app.AlarmManager;
      import android.app.Notification;
      import android.app.NotificationChannel;
      import android.app.NotificationManager;
      import android.app.PendingIntent;
      import android.app.Service;
      import android.content.Intent;
      import android.content.SharedPreferences;
      import android.os.Build;
      import android.os.Handler;
      import android.os.IBinder;
      import android.os.Looper;
      import android.os.SystemClock;

      /**
       * Persistent foreground watchdog (START_STICKY). Polls every 15s; if the app
       * is not in the foreground AND auto-relaunch is enabled it schedules a restart
       * via AlarmManager (1s delay). Android restarts this service automatically
       * after OOM kills.
       *
       * Crash-recovery alarm: armed in onCreate() and re-armed each loop tick so it is
       * always CHECK_MS + 10s ahead. If this service is hard-killed between ticks the
       * alarm fires within 25s and relaunches the app via BootLaunchService.
       */
      public class WatchdogService extends Service {
          private static final String CHANNEL_ID = "digipal_watchdog";
          private static final int NOTIF_ID = 1002;
          private static final long CHECK_MS = 15_000L;
          private static final long RESTART_MS = 1_000L;
          private static final String PREFS_NAME = "DigipalPrefs";
          private static final String KEY_AUTO_RELAUNCH = "auto_relaunch";
          private Handler handler;
          private Runnable loop;
          private PendingIntent crashAlarmPi;

          @Override
          public void onCreate() {
              super.onCreate();
              ensureChannel();
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                  startForeground(NOTIF_ID, buildNotif(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
              } else {
                  startForeground(NOTIF_ID, buildNotif());
              }
              handler = new Handler(Looper.getMainLooper());
              loop = new Runnable() {
                  @Override public void run() {
                      if (!inForeground() && isAutoRelaunchEnabled()) restart();
                      // Re-arm crash alarm each cycle so it is always fresh even if this
                      // service runs for a long time without restarting.
                      if (isAutoRelaunchEnabled() && crashAlarmPi != null) armCrashAlarm();
                      handler.postDelayed(this, CHECK_MS);
                  }
              };
              handler.postDelayed(loop, CHECK_MS);

              // Standing crash-recovery alarm: fires CHECK_MS+10s from now and is
              // re-armed each loop tick. Ensures app relaunches even if this service
              // is hard-killed before the periodic Handler loop gets a chance to run.
              // Only armed when auto-relaunch is enabled.
              if (isAutoRelaunchEnabled()) {
                  int cpf = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
                  crashAlarmPi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                      ? PendingIntent.getForegroundService(this, 99, new Intent(this, BootLaunchService.class), cpf)
                      : PendingIntent.getService(this, 99, new Intent(this, BootLaunchService.class), cpf);
                  armCrashAlarm();
              }
          }

          @Override public int onStartCommand(Intent i, int f, int s) { return START_STICKY; }
          @Override public IBinder onBind(Intent i) { return null; }
          @Override public void onDestroy() {
              if (handler != null) handler.removeCallbacks(loop);
              if (crashAlarmPi != null) {
                  AlarmManager am2 = (AlarmManager) getSystemService(ALARM_SERVICE);
                  if (am2 != null) am2.cancel(crashAlarmPi);
              }
              super.onDestroy();
          }

          private boolean isAutoRelaunchEnabled() {
              SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
              return prefs.getBoolean(KEY_AUTO_RELAUNCH, true);
          }

          private boolean inForeground() {
              // Use the Activity's own visibility flag instead of process importance.
              // IMPORTANCE_FOREGROUND_SERVICE (125) is wrong: while WatchdogService runs
              // as a foreground service the process stays at 125 even when the Activity
              // is backgrounded by the Home button, so the old check always returned true
              // and never triggered a relaunch.
              return MainActivity.activityVisible;
          }

          private void armCrashAlarm() {
              AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
              if (am == null || crashAlarmPi == null) return;
              // Fire CHECK_MS + 10s from now. Re-armed each loop tick so the window
              // is always fresh. Worst-case delay after a hard kill = CHECK_MS + 10s = 25s.
              long earliest = SystemClock.elapsedRealtime() + CHECK_MS + 10_000L;
              am.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, earliest, 10_000L, crashAlarmPi);
          }

          private void restart() {
              // On Android O+ the alarm must use getForegroundService so the
              // PendingIntent can promote BootLaunchService to foreground.
              Intent i = new Intent(this, BootLaunchService.class);
              int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
              PendingIntent pi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                  ? PendingIntent.getForegroundService(this, 2, i, f)
                  : PendingIntent.getService(this, 2, i, f);
              AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
              if (am == null) return;
              long at = System.currentTimeMillis() + RESTART_MS;
              try {
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                  else am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
              } catch (SecurityException e) { am.set(AlarmManager.RTC_WAKEUP, at, pi); }
          }

          private void ensureChannel() {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                  NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                  if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                      NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Digipal Player", NotificationManager.IMPORTANCE_MIN);
                      ch.setShowBadge(false); ch.setSound(null,null); ch.enableLights(false); ch.enableVibration(false);
                      nm.createNotificationChannel(ch);
                  }
              }
          }

          private Notification buildNotif() {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                  return new Notification.Builder(this, CHANNEL_ID).setContentTitle("Digipal Player").setContentText("Running").setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build();
              return new Notification.Builder(this).setContentTitle("Digipal Player").setContentText("Running").setSmallIcon(android.R.drawable.ic_media_play).setPriority(Notification.PRIORITY_MIN).setOngoing(true).build();
          }
      }
  