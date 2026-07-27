package com.digipal.signage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import android.webkit.WebView;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Listens for WebView provider package changes and Digipal's own APK replacement,
 * then schedules a player relaunch via BootLaunchService.schedulePlayerLaunch().
 *
 * Why this is needed:
 *   Android kills the app process with SIGKILL when the WebView provider
 *   (com.google.android.webview / com.android.chrome / trichrome) is updated.
 *   Neither the crash-exception handler nor WatchdogService.onDestroy() is
 *   called — the process simply vanishes. The WatchdogService deadman alarm
 *   recovers the app within ~25 s, but this receiver shortens that to 3 s
 *   and adds explicit recovery logging.
 *
 * The receiver runs in a fresh process context on receipt (not the app's
 * main process), so it is immune to the SIGKILL that killed the player.
 *
 * Only reacts when auto-relaunch is enabled in SharedPreferences.
 */
public class PackageUpdateReceiver extends BroadcastReceiver {

    private static final String TAG = "DigipalRecovery";
    private static final String PREFS_NAME = "DigipalPrefs";
    private static final long RELAUNCH_DELAY_MS = 3_000L;

    /** Packages whose update/replacement should trigger a player relaunch. */
    private static final Set<String> WEBVIEW_PACKAGES = new HashSet<>(Arrays.asList(
            "com.google.android.webview",
            "com.android.webview",
            "com.google.android.trichromelibrary",
            "com.android.chrome"
    ));

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        Log.i(TAG, "PackageUpdateReceiver: action=" + action);

        // MY_PACKAGE_REPLACED — Digipal APK itself was updated.
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Log.i(TAG, "PackageUpdateReceiver: own APK replaced, scheduling relaunch");
            scheduleIfEnabled(context, "my_package_replaced");
            return;
        }

        // PACKAGE_REPLACED / PACKAGE_ADDED / PACKAGE_CHANGED — filter by package name.
        android.net.Uri data = intent.getData();
        String pkg = (data != null) ? data.getSchemeSpecificPart() : null;
        Log.i(TAG, "PackageUpdateReceiver: package=" + pkg);
        if (pkg == null) return;

        if (WEBVIEW_PACKAGES.contains(pkg)) {
            boolean autoRelaunch = isAutoRelaunchEnabled(context);
            Log.i(TAG, "PackageUpdateReceiver: matched WebView provider package=" + pkg
                    + " autoRelaunch=" + autoRelaunch);
            logCurrentWebViewProvider();
            scheduleIfEnabled(context, "webview_package_replaced");
        } else {
            Log.d(TAG, "PackageUpdateReceiver: ignoring non-WebView package=" + pkg);
        }
    }

    private void scheduleIfEnabled(Context context, String reason) {
        boolean enabled = isAutoRelaunchEnabled(context);
        Log.i(TAG, "PackageUpdateReceiver: scheduleIfEnabled reason=" + reason
                + " autoRelaunch=" + enabled);
        if (!enabled) return;
        BootLaunchService.schedulePlayerLaunch(context, reason, RELAUNCH_DELAY_MS);
    }

    private boolean isAutoRelaunchEnabled(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getBoolean("auto_relaunch", false);
        } catch (Exception e) {
            Log.w(TAG, "Failed to read auto_relaunch pref", e);
            return false;
        }
    }

    /** Logs the current WebView provider name + version (API 26+). */
    private void logCurrentWebViewProvider() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                android.content.pm.PackageInfo pi = WebView.getCurrentWebViewPackage();
                if (pi != null) {
                    Log.i(TAG, "PackageUpdateReceiver: current WebView provider="
                            + pi.packageName + " version=" + pi.versionName);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to query current WebView package", e);
            }
        }
    }
}
