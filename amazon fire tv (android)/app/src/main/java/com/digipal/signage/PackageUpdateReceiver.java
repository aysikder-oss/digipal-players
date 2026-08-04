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
 * then triggers a player relaunch via a short background delay.
 *
 * RECOVERY TIMING
 * ---------------
 * Previous approach: AlarmManager.setWindow(trigger+3000ms, window=5000ms) fired
 * RelaunchReceiver after 3–8 s, with observed end-to-end recovery of 9–10 s.
 *
 * Current approach:
 *   1. goAsync() — extends the receiver's process lifetime up to ~60 s.
 *   2. Background thread sleeps exactly RELAUNCH_DELAY_MS (3 s).
 *   3. RelaunchReceiver.postRelaunchNotification() is called directly:
 *        Layer 1: AppTask.moveToFront()         — if app is backgrounded but alive.
 *        Layer 2: SYSTEM_ALERT_WINDOW + startActivity() — if app was killed.
 *        Layer 3: FSI notification              — last resort.
 *   4. PendingResult.finish() releases the goAsync wakelock.
 *   Expected end-to-end recovery: ~3.2 s.
 *
 * WHY NOT A FOREGROUND SERVICE?
 *   Android 12+ (API 31) restricts FGS starts from background processes.
 *   PACKAGE_REPLACED for a third-party package (e.g. WebView) is NOT in the
 *   exemption list, so startForegroundService() throws
 *   ForegroundServiceStartNotAllowedException.  goAsync() has no such restriction
 *   and keeps the process alive for the brief delay without a visible notification.
 *
 * PROCESS CONTEXT
 * ---------------
 * When Android kills the app with SIGKILL (WebView update), this receiver runs
 * in a fresh process. AppTask.moveToFront() may find no task and falls through to
 * the SYSTEM_ALERT_WINDOW startActivity() path, which works as long as the
 * SYSTEM_ALERT_WINDOW permission is granted (confirmed working in v3.16.113).
 */
public class PackageUpdateReceiver extends BroadcastReceiver {

    private static final String TAG = "DigipalRecovery";
    private static final String PREFS_NAME = "DigipalPrefs";

    /**
     * Intentional delay before relaunch.
     * Gives Android enough time to finish swapping the WebView provider before
     * MainActivity tries to create a new WebView instance.
     */
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

        String reason = null;

        // Debug-only: simulate a WebView provider replacement so testers can exercise the
        // recovery flow via adb on Android 14+ without sending a protected system broadcast.
        // Usage:
        //   adb shell am broadcast         //     -a com.digipal.signage.debug.SIMULATE_WEBVIEW_PACKAGE_REPLACED         //     -p com.digipal.signage.debug
        if (com.digipal.signage.BuildConfig.DEBUG
                && "com.digipal.signage.debug.SIMULATE_WEBVIEW_PACKAGE_REPLACED".equals(action)) {
            Log.i(TAG, "PackageUpdateReceiver: DEBUG simulate webview package replaced");
            logCurrentWebViewProvider();
            reason = "simulated_webview_package_replaced";

        // MY_PACKAGE_REPLACED — Digipal APK itself was updated.
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Log.i(TAG, "PackageUpdateReceiver: own APK replaced, scheduling relaunch");
            reason = "my_package_replaced";

        // PACKAGE_REPLACED / PACKAGE_ADDED / PACKAGE_CHANGED — filter by package name.
        } else {
            android.net.Uri data = intent.getData();
            String pkg = (data != null) ? data.getSchemeSpecificPart() : null;
            Log.i(TAG, "PackageUpdateReceiver: package=" + pkg);
            if (pkg == null) return;
            if (WEBVIEW_PACKAGES.contains(pkg)) {
                logCurrentWebViewProvider();
                reason = "webview_package_replaced";
                Log.i(TAG, "PackageUpdateReceiver: matched WebView provider package=" + pkg);
            } else {
                Log.d(TAG, "PackageUpdateReceiver: ignoring non-WebView package=" + pkg);
                return;
            }
        }

        if (!isAutoRelaunchEnabled(context)) {
            Log.i(TAG, "PackageUpdateReceiver: auto_relaunch disabled — skipping");
            return;
        }

        // goAsync() extends the receiver's process lifetime so the background
        // thread can complete the 3-second delay without being killed by Android.
        // Must be called synchronously from onReceive(); the PendingResult is
        // handed off to the thread which calls finish() after postRelaunchNotification().
        final PendingResult pendingResult = goAsync();
        final String finalReason = reason;
        final Context appContext = context.getApplicationContext();

        new Thread(() -> {
            try {
                Log.i(TAG, "PackageUpdateReceiver: delaying " + RELAUNCH_DELAY_MS
                        + "ms before relaunch, reason=" + finalReason);
                Thread.sleep(RELAUNCH_DELAY_MS);
                RelaunchReceiver.postRelaunchNotification(appContext, finalReason);
            } catch (InterruptedException e) {
                Log.w(TAG, "PackageUpdateReceiver: delay interrupted", e);
                RelaunchReceiver.postRelaunchNotification(appContext, finalReason);
            } catch (Exception e) {
                Log.w(TAG, "PackageUpdateReceiver: postRelaunchNotification failed", e);
            } finally {
                pendingResult.finish();
            }
        }, "DigipalRelaunchDelay").start();
    }

    private boolean isAutoRelaunchEnabled(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean enabled = prefs.getBoolean("auto_relaunch", false);
            Log.i(TAG, "PackageUpdateReceiver: auto_relaunch=" + enabled);
            return enabled;
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
