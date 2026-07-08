package com.digipal.signage;

import android.app.Application;

/**
 * Application-level entry point. Registered via android:name in
 * AndroidManifest.xml so onCreate() runs before ServerSetupActivity /
 * MainActivity, regardless of which one the OS launches first.
 *
 * Used to pre-warm the WebView renderer process (task #1944) as early as
 * possible in the app lifecycle, in parallel with other startup work, so the
 * real player WebView created shortly after doesn't pay the cold-start cost.
 */
public class DigipalApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        WebViewWarmer.warmUp(this);
    }
}
