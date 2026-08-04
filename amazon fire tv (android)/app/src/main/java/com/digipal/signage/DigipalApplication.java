package com.digipal.signage;

import android.app.Application;
import android.os.Build;
import android.os.StrictMode;
import android.webkit.WebView;

/**
 * Application-level entry point. Registered via android:name in
 * AndroidManifest.xml so onCreate() runs before ServerSetupActivity /
 * MainActivity, regardless of which one the OS launches first.
 *
 * Used to pre-warm the WebView renderer process (task #1944) as early as
 * possible in the app lifecycle, in parallel with other startup work, so the
 * real player WebView created shortly after doesn't pay the cold-start cost.
 *
 * Also enables debug-only diagnostics: WebView DevTools (chrome://inspect),
 * StrictMode thread/VM policy. These are gated on BuildConfig.DEBUG and are
 * complete no-ops in release builds.
 */
public class DigipalApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        if (BuildConfig.DEBUG) {
            // Enable chrome://inspect WebView remote debugging.
            // Safe: BuildConfig.DEBUG is false in release builds.
            WebView.setWebContentsDebuggingEnabled(true);

            // Detect disk/network on main thread and leaked closables.
            // penaltyLog() writes to logcat without crashing — safe for
            // manual testing. Use `adb logcat -s StrictMode` to filter.
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build());
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build());
        }

        WebViewWarmer.warmUp(this);
    }
}
