package com.nexuscast.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
 */
public final class WebViewWarmer {
    private static final String TAG = "WebViewWarmer";
    private static volatile boolean warmed = false;

    private WebViewWarmer() {}

    public static void warmUp(Context context) {
        if (warmed) return;
        warmed = true;
        try {
            final Context appContext = context.getApplicationContext();
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
                    });
                    warmWebView.loadUrl("about:blank");
                    // Safety net in case onPageFinished never fires on some OEM WebViews.
                    new Handler(Looper.getMainLooper()).postDelayed(destroyOnce, 3000);
                } catch (Throwable t) {
                    Log.w(TAG, "WebView pre-warm failed (non-fatal): " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "WebView pre-warm scheduling failed: " + t.getMessage());
        }
    }
}
