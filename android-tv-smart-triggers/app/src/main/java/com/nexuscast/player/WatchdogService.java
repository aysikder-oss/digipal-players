package com.nexuscast.player;

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
   * Persistent foreground watchdog (START_STICKY). Polls every 10s; if the app
   * is not in the foreground it schedules a restart via AlarmManager (1s delay).
   * Android restarts this service automatically after OOM kills.
   */
  public class WatchdogService extends Service {
      private static final String CHANNEL_ID = "digipal_watchdog";
      private static final int NOTIF_ID = 1002;
      private static final long CHECK_MS = 30_000L;
      private static final long RESTART_MS = 1_000L;
      private Handler handler;
      private Runnable loop;

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
                  if (!inForeground()) restart();
                  handler.postDelayed(this, CHECK_MS);
              }
          };
          handler.postDelayed(loop, CHECK_MS);
      }

      @Override public int onStartCommand(Intent i, int f, int s) { return START_STICKY; }
      @Override public IBinder onBind(Intent i) { return null; }
      @Override public void onDestroy() { if (handler!=null) handler.removeCallbacks(loop); super.onDestroy(); }

      private boolean inForeground() {
          ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
          if (am == null) return true;
          List<ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
          if (ps == null) return true;
          for (ActivityManager.RunningAppProcessInfo p : ps)
              if (p.processName.equals(getPackageName()) && p.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) return true;
          return false;
      }

      private void restart() {
          Intent i = new Intent(this, MainActivity.class);
          i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
          int f = PendingIntent.FLAG_ONE_SHOT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
          PendingIntent pi = PendingIntent.getActivity(this, 2, i, f);
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
  