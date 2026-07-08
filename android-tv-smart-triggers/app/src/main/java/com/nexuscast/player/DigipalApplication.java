package com.nexuscast.player;

import android.app.Application;

/**
 * Application-level entry point. Registered via android:name in
 * AndroidManifest.xml so onCreate() runs before MainActivity (the launcher
 * activity in this variant).
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
