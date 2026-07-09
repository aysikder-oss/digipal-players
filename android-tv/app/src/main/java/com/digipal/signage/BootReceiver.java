package com.digipal.signage;

  import android.content.BroadcastReceiver;
  import android.content.Context;
  import android.content.Intent;
  import android.content.SharedPreferences;
  import android.os.Build;
  import android.util.Log;

  /**
   * Listens for device boot and starts BootLaunchService to open MainActivity.
   * Android 10+ / FireOS 7+ silently drops background startActivity() for
   * non-system apps. A foreground service bypasses this restriction.
   */
  public class BootReceiver extends BroadcastReceiver {

      private static final String PREFS_NAME = "DigipalPrefs";
      private static final String KEY_AUTO_RELAUNCH = "auto_relaunch";

      @Override
      public void onReceive(Context context, Intent intent) {
          if (intent == null) return;
          String action = intent.getAction();
          if (action == null) return;

          boolean isStandardBoot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                  || "android.intent.action.QUICKBOOT_POWERON".equals(action);
          if (!isStandardBoot) return;

          try {
              SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
              if (!prefs.getBoolean(KEY_AUTO_RELAUNCH, false)) return;
          } catch (Exception e) {
              Log.w("BootReceiver", "Failed to read auto-relaunch preference", e);
              return;
          }

          Intent svc = new Intent(context, BootLaunchService.class);
          try {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(svc);
              else context.startService(svc);
          } catch (Exception e) {
              Log.w("BootReceiver", "startForegroundService failed, falling back to startActivity", e);
              try {
                  Intent launch = new Intent(context, MainActivity.class);
                  launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                  context.startActivity(launch);
              } catch (Exception ex) {
                  Log.w("BootReceiver", "fallback startActivity failed", ex);
              }
          }
      }
  }
