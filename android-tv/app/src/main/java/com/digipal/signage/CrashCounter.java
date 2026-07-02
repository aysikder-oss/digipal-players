package com.digipal.signage;

  import android.content.Context;
  import android.content.SharedPreferences;

  /**
   * Tracks crash frequency in SharedPreferences so WorkManager recovery can
   * apply exponential back-off when the player enters a crash loop.
   *
   * Fields stored under "DigipalPrefs":
   *   crash_counter_count        — crashes recorded in the current window
   *   crash_counter_window_start — epoch-ms of the first crash in this window
   *   crash_counter_max_exceeded — true once the count exceeds MAX_CRASHES
   */
  public class CrashCounter {
      private static final String PREFS_NAME = "DigipalPrefs";
      static final String KEY_CRASH_COUNT        = "crash_counter_count";
      static final String KEY_CRASH_WINDOW_START = "crash_counter_window_start";
      static final String KEY_MAX_EXCEEDED       = "crash_counter_max_exceeded";

      /** Sliding window duration: 100 seconds. */
      private static final long CRASH_WINDOW_MS = 300_000L; // Fix 10: widened from 100s to avoid false positives on slow-boot devices
      /** Crashes within the window that trigger max-exceeded state. */
      private static final int MAX_CRASHES = 5;

      /**
       * Increments the crash counter and returns true if max-exceeded was just triggered.
       * Uses commit() (synchronous) so the value survives immediate process death.
       */
      public static boolean recordCrash(Context ctx) {
          SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
          long now = System.currentTimeMillis();
          long windowStart = prefs.getLong(KEY_CRASH_WINDOW_START, 0L);
          int count = prefs.getInt(KEY_CRASH_COUNT, 0);

          if (now - windowStart > CRASH_WINDOW_MS) {
              windowStart = now;
              count = 0;
          }

          count++;
          boolean maxExceeded = count > MAX_CRASHES;
          prefs.edit()
              .putInt(KEY_CRASH_COUNT, count)
              .putLong(KEY_CRASH_WINDOW_START, windowStart)
              .putBoolean(KEY_MAX_EXCEEDED, maxExceeded)
              .commit();
          return maxExceeded;
      }

      public static boolean isMaxExceeded(Context ctx) {
          return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
              .getBoolean(KEY_MAX_EXCEEDED, false);
      }

      /** Called on clean startup to reset all counters. */
      public static void reset(Context ctx) {
          ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
              .putInt(KEY_CRASH_COUNT, 0)
              .putLong(KEY_CRASH_WINDOW_START, 0L)
              .putBoolean(KEY_MAX_EXCEEDED, false)
              .commit();
      }
  }
  