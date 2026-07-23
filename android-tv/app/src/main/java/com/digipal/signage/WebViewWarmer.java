package com.digipal.signage;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pre-warms the WebView renderer process at app startup, before any real
 * player content is requested (task #1944 — WebView renderer priority &
 * warm-up).
 *
 * Creating the first WebView in a process triggers Chromium's sandboxed
 * renderer to spawn/load, which can take a few hundred ms on lower-power TV
 * boxes. Doing that once with a throwaway, invisible WebView while the rest
 * of app startup (Room DB init, foreground service start, etc.) proceeds in
 * parallel means the *real* player WebView created shortly after (in
 * ServerSetupActivity/MainActivity) already has a warm renderer to attach
 * to, instead of paying that cold-start cost on the critical path.
 *
 * On low-RAM devices (< 512 MB free or isLowRamDevice flag set) the warm-up
 * is skipped entirely: the OS would OOM-kill the throwaway renderer process
 * immediately, wasting both RAM and startup time and producing a spurious
 * "Renderer process crash" logcat entry with no user-visible effect.
 */
public final class WebViewWarmer {
    private static final String TAG = "WebViewWarmer";
    private static final long MIN_FREE_MB = 512L;
    private static volatile boolean warmed = false;

    private WebViewWarmer() {}

    public static void warmUp(Context context) {
        if (warmed) return;
        warmed = true;
        try {
            final Context appContext = context.getApplicationContext();
            if (isLowMemoryDevice(appContext)) {
                Log.i(TAG, "Skipping WebView pre-warm on low-RAM device");
                return;
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    final WebView warmWebView = new WebView(appContext);
                    final AtomicBoolean destroyed = new AtomicBoolean(false);
                    final Runnable destroyOnce = () -> {
                        if (destroyed.compareAndSet(false, true)) {
                            try {
                                warmWebView.stopLoading();
                                warmWebView.setWebViewClient(null);
                                warmWebView.destroy();
                            } catch (Throwable ignored) {}
                        }
                    };
                    warmWebView.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(WebView view, String url) {
                            destroyOnce.run();
                        }

                        @Override
                        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                            // Renderer was OOM-killed before warm-up completed — clean up and
                            // return true so Android does NOT finish any Activity.
                            Log.i(TAG, "Pre-warm renderer gone (non-fatal), cleaning up");
                            destroyed.set(true);
                            try { view.destroy(); } catch (Throwable ignored) {}
                            return true;
                        }
                    });
                    warmWebView.loadUrl("about:blank");
                    // Safety net: onPageFinished may never fire on some OEM WebViews.
                    new Handler(Looper.getMainLooper()).postDelayed(destroyOnce, 3000);
                } catch (Throwable t) {
                    Log.w(TAG, "WebView pre-warm failed (non-fatal): " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "WebView pre-warm scheduling failed: " + t.getMessage());
        }
    }

    private static boolean isLowMemoryDevice(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        if (am.isLowRamDevice()) return true;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return (mi.availMem / (1024L * 1024L)) < MIN_FREE_MB;
    }
}
