package com.digipal.signage;

  import android.content.Context;
  import android.content.Intent;
  import android.os.Build;
  import androidx.annotation.NonNull;
  import androidx.work.Worker;
  import androidx.work.WorkerParameters;

  /**
   * Periodic WorkManager Worker that runs every 15 minutes as an independent safety
   * net. If the player is not in the foreground it relaunches the main activity.
   */
  public class BackupRecoverWorker extends Worker {
      public static final String TAG = "DigipalBackupRecoverWorker";

      public BackupRecoverWorker(@NonNull Context context, @NonNull WorkerParameters params) {
          super(context, params);
      }

      @NonNull
      @Override
      public Result doWork() {
          if (MainActivity.activityVisible) {
              // Player is visible — nothing to do.
              return Result.success();
          }
          try {
              Context ctx = getApplicationContext();
              boolean enabled = ctx.getSharedPreferences("DigipalPrefs", Context.MODE_PRIVATE)
                      .getBoolean("auto_relaunch", false);
              if (!enabled) return Result.success();
              Intent svc = new Intent(ctx, BootLaunchService.class);
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                  ctx.startForegroundService(svc);
              } else {
                  ctx.startService(svc);
              }
          } catch (Throwable e) {
              android.util.Log.e("Digipal", "BackupRecoverWorker: launch failed", e);
          }
          return Result.success();
      }
  }
  