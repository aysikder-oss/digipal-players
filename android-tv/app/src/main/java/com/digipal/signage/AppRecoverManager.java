package com.digipal.signage;

  import android.content.Context;
  import androidx.work.ExistingPeriodicWorkPolicy;
  import androidx.work.ExistingWorkPolicy;
  import androidx.work.OneTimeWorkRequest;
  import androidx.work.PeriodicWorkRequest;
  import androidx.work.WorkManager;
  import java.util.concurrent.TimeUnit;

  /**
   * Central manager for WorkManager-based crash recovery.
   *
   * Call {@link #onCleanStart(Context)} on every successful launch (resets counter +
   * cancels pending one-shot workers + ensures the backup periodic worker is live).
   *
   * Call {@link #scheduleRecovery(Context)} on every abnormal exit (crash handler or
   * onDestroy when the user did not explicitly close the app).
   */
  public class AppRecoverManager {
      /** Recovery delay after a normal crash (30 seconds). */
      private static final long NORMAL_DELAY_SECONDS  = 30L;
      /** Recovery delay after max-exceeded crash loop (10 minutes). */
      private static final long MAX_DELAY_SECONDS     = 600L;

      /**
       * Increments the crash counter and enqueues a one-shot RecoverWorker.
       * Delay is 30 s normally, 600 s when the counter has been exceeded.
       */
      public static void scheduleRecovery(Context ctx) {
          Context appContext = ctx.getApplicationContext();
          boolean maxExceeded = CrashCounter.recordCrash(appContext);
          long delaySec = maxExceeded ? MAX_DELAY_SECONDS : NORMAL_DELAY_SECONDS;
          try {
              OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(RecoverWorker.class)
                  .setInitialDelay(delaySec, TimeUnit.SECONDS)
                  .addTag(RecoverWorker.TAG)
                  .build();
              WorkManager.getInstance(appContext)
                  .enqueueUniqueWork(RecoverWorker.TAG, ExistingWorkPolicy.REPLACE, work);
          } catch (Throwable e) {
              android.util.Log.e("Digipal", "AppRecoverManager.scheduleRecovery failed", e);
          }
      }

      /**
       * Called on clean startup. Cancels any pending one-shot recovery, resets the
       * crash counter, and ensures the background periodic worker is registered.
       */
      public static void onCleanStart(Context ctx) {
          Context appContext = ctx.getApplicationContext();
          try {
              WorkManager.getInstance(appContext).cancelUniqueWork(RecoverWorker.TAG);
          } catch (Throwable e) {
              android.util.Log.e("Digipal", "AppRecoverManager.onCleanStart cancel failed", e);
          }
          CrashCounter.reset(appContext);
          scheduleBackupWorker(appContext);
      }

      /**
       * Registers the 15-minute periodic safety-net worker. Uses KEEP so an existing
       * registration is not disturbed if the app restarts cleanly.
       */
      static void scheduleBackupWorker(Context ctx) {
          Context appContext = ctx.getApplicationContext();
          try {
              PeriodicWorkRequest backupWork = new PeriodicWorkRequest.Builder(
                  BackupRecoverWorker.class, 15L, TimeUnit.MINUTES)
                  .addTag(BackupRecoverWorker.TAG)
                  .build();
              WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                  BackupRecoverWorker.TAG,
                  ExistingPeriodicWorkPolicy.KEEP,
                  backupWork
              );
          } catch (Throwable e) {
              android.util.Log.e("Digipal", "AppRecoverManager.scheduleBackupWorker failed", e);
          }
      }
  }
  