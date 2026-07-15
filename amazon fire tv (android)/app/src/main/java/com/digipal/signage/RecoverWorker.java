package com.nexuscast.player;

  import android.content.Context;
  import android.content.Intent;
  import android.os.Build;
  import androidx.annotation.NonNull;
  import androidx.work.Worker;
  import androidx.work.WorkerParameters;

  /**
   * WorkManager one-shot Worker that restarts the player after a crash.
   * Scheduled by AppRecoverManager with a 30-second initial delay (or 600 seconds
   * when the crash counter has been exceeded).
   */
  public class RecoverWorker extends Worker {
      public static final String TAG = "DigipalRecoverWorker";

      public RecoverWorker(@NonNull Context context, @NonNull WorkerParameters params) {
          super(context, params);
      }

      @NonNull
      @Override
      public Result doWork() {
          if (MainActivity.activityAlive) {
              // Player is already running — nothing to do.
              return Result.success();
          }
          try {
              Context ctx = getApplicationContext();
              Intent svc = new Intent(ctx, BootLaunchService.class);
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                  ctx.startForegroundService(svc);
              } else {
                  ctx.startService(svc);
              }
          } catch (Throwable e) {
              android.util.Log.e("Digipal", "RecoverWorker: launch failed", e);
              return Result.retry();
          }
          return Result.success();
      }
  }
  