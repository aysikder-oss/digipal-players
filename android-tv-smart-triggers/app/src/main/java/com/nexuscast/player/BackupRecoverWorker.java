package com.nexuscast.player;

  import android.content.Context;
  import android.content.Intent;
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
              Intent intent = new Intent(ctx, MainActivity.class);
              intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
              ctx.startActivity(intent);
          } catch (Throwable e) {
              android.util.Log.e("Nexuscast", "BackupRecoverWorker: launch failed", e);
          }
          return Result.success();
      }
  }
  