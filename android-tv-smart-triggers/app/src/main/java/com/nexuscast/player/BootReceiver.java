package com.nexuscast.player;

  import android.content.BroadcastReceiver;
  import android.content.Context;
  import android.content.Intent;
  import android.content.SharedPreferences;
  import android.os.Build;

  /**
   * Listens for device boot and starts BootLaunchService to open MainActivity.
   * Android 10+ / FireOS 7+ silently drops background startActivity() for
   * non-system apps. A foreground service bypasses this restriction.
   * LOCKED_BOOT_COMPLETED fires before credential unlock; skip prefs check there.
   */
  public class BootReceiver extends BroadcastReceiver {

      private static final String PREFS_NAME = "DigipalPrefs";
      private static final String KEY_AUTO_RELAUNCH = "auto_relaunch";

      @Override
      public void onReceive(Context context, Intent intent) {
          String action = intent.getAction();
          if (action == null) return;

          boolean isLockedBoot = "android.intent.action.LOCKED_BOOT_COMPLETED".equals(action);
          boolean isStandardBoot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                  || "android.intent.action.QUICKBOOT_POWERON".equals(action);
          if (!isLockedBoot && !isStandardBoot) return;

          if (isStandardBoot) {
              try {
                  SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                  if (!prefs.getBoolean(KEY_AUTO_RELAUNCH, false)) return;
              } catch (Exception e) { return; }
          }

          Intent svc = new Intent(context, BootLaunchService.class);
          try {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(svc);
              else context.startService(svc);
          } catch (Exception e) {
              try {
                  Intent launch = new Intent(context, MainActivity.class);
                  launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                  context.startActivity(launch);
              } catch (Exception ignored) {}
          }
      }
  }
  