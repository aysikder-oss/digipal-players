package com.nexuscast.player;

  import android.app.Notification;
  import android.app.NotificationChannel;
  import android.app.NotificationManager;
  import android.app.Service;
  import android.content.Intent;
  import android.os.Build;
  import android.os.IBinder;

  /**
   * Short-lived foreground service that opens MainActivity on boot.
   * Foreground context allows startActivity() on all API levels.
   * Also starts the persistent WatchdogService, then stops itself.
   */
  public class BootLaunchService extends Service {
      private static final String CHANNEL_ID = "digipal_boot";
      private static final int NOTIF_ID = 1001;

      @Override
      public int onStartCommand(Intent intent, int flags, int startId) {
          ensureChannel();
          startForeground(NOTIF_ID, buildNotif());
          try {
              Intent launch = new Intent(this, MainActivity.class);
              launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
              startActivity(launch);
          } catch (Exception ignored) {}
          try {
              Intent wd = new Intent(this, WatchdogService.class);
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(wd);
              else startService(wd);
          } catch (Exception ignored) {}
          stopSelf();
          return START_NOT_STICKY;
      }

      @Override public IBinder onBind(Intent i) { return null; }

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
              return new Notification.Builder(this, CHANNEL_ID).setContentTitle("Digipal Player").setContentText("Starting…").setSmallIcon(android.R.drawable.ic_media_play).build();
          return new Notification.Builder(this).setContentTitle("Digipal Player").setContentText("Starting…").setSmallIcon(android.R.drawable.ic_media_play).setPriority(Notification.PRIORITY_MIN).build();
      }
  }
  