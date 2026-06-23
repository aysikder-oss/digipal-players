package com.nexuscast.player;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import android.net.wifi.WifiManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Base64;
import android.view.PixelCopy;
import java.io.ByteArrayOutputStream;
import android.os.Handler;
import android.os.Looper;
  import android.text.format.Formatter;
  public class MainActivity extends Activity {

    /** Set true in onResume, false in onStop — read by WatchdogService.inForeground(). */
    public static volatile boolean activityVisible = false;
    /** Set true in onCreate, false in onDestroy — used by WatchdogService to detect real crashes vs Home-press. */
    public static volatile boolean activityAlive = false;

    private WebView webView;
    private FrameLayout rootLayout;
    private FrameLayout errorContainer;
    private PowerManager.WakeLock wakeLock;
    private static final String PREFS_NAME = "DigipalPrefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_AUTO_RELAUNCH = "auto_relaunch";
    private static final int PERMISSION_REQUEST_CAMERA = 1001;
    private boolean isUserClosing = false;
    private View customView;
    private FrameLayout customViewContainer;
    private HardwareManager hardwareManager;

    private static final java.util.regex.Pattern PRIVATE_IP_PATTERN = java.util.regex.Pattern.compile(
        "^(10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}" +
        "|172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}" +
        "|192\\.168\\.\\d{1,3}\\.\\d{1,3}" +
        "|127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}" +
        "|localhost" +
        "|\\[::1\\])$"
    );
    private MediaDownloadManager mediaDownloadManager;
    private int dpadPressCount = 0;
    private long dpadFirstPressMs = 0L;
    private android.view.View diagnosticsOverlay = null;
    private android.os.Handler diagDismissHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activityAlive = true;
        try {

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_FULLSCREEN |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try { getWindow().setSustainedPerformanceMode(true); } catch (Throwable ignored) {}
        }

        hideSystemUI();

        FrameLayout root = new FrameLayout(this);
        rootLayout = root;
        root.setBackgroundColor(Color.parseColor("#0a0e1a"));

        try {
              webView = new WebView(this);
          } catch (Throwable e) {
              android.widget.TextView errTv = new android.widget.TextView(this);
              errTv.setText("WebView unavailable.\n\nPlease update Android System WebView from the Play Store, then relaunch Digipal.");
              errTv.setTextColor(android.graphics.Color.WHITE);
              errTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f);
              errTv.setPadding(80, 80, 80, 80);
              errTv.setGravity(android.view.Gravity.CENTER);
              root.addView(errTv);
              customViewContainer = new FrameLayout(this);
        customViewContainer.setBackgroundColor(Color.BLACK);
        customViewContainer.setVisibility(View.GONE);
        root.addView(customViewContainer, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        setContentView(root);
              return;
          }
          setupWebView();
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();
        root.addView(webView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        errorContainer = new FrameLayout(this);
        errorContainer.setBackgroundColor(Color.parseColor("#0a0e1a"));
        errorContainer.setVisibility(View.GONE);
        root.addView(errorContainer, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        setContentView(root);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "nexuscast:wakelock"
        );

        requestCameraPermission();

        hardwareManager = new HardwareManager(this, new HardwareManager.HardwareListener() {
            @Override
            public void onDeviceConnected(JSONObject device) {
                runOnUiThread(() -> {
                    String js = "javascript:window.dispatchEvent(new CustomEvent('hw:deviceConnected', {detail: " + device.toString() + "}))";
                    webView.evaluateJavascript(js, null);
                });
            }

            @Override
            public void onDeviceDisconnected(JSONObject device) {
                runOnUiThread(() -> {
                    String js = "javascript:window.dispatchEvent(new CustomEvent('hw:deviceDisconnected', {detail: " + device.toString() + "}))";
                    webView.evaluateJavascript(js, null);
                });
            }

            @Override
            public void onSignalCaptured(JSONObject signal) {
                runOnUiThread(() -> {
                    String js = "javascript:window.dispatchEvent(new CustomEvent('hw:signalCaptured', {detail: " + signal.toString() + "}))";
                    webView.evaluateJavascript(js, null);
                });
            }

            @Override
            public void onSignalEvent(JSONObject signal) {
                runOnUiThread(() -> {
                    String js = "javascript:window.dispatchEvent(new CustomEvent('hw:signalEvent', {detail: " + signal.toString() + "}))";
                    webView.evaluateJavascript(js, null);
                });
            }
        });
        try { hardwareManager.start(); } catch (Throwable ignored) {}

        mediaDownloadManager = new MediaDownloadManager(this);
        mediaDownloadManager.setWebView(webView);
        mediaDownloadManager.cleanupOrphans();

        
          // Start crash watchdog — relaunches app within 10s if it crashes or is killed.
          Intent watchdogIntent = new Intent(this, WatchdogService.class);
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              startForegroundService(watchdogIntent);
          } else {
              startService(watchdogIntent);
          }
  
        String serverUrl = getServerUrl();
        loadPlayerUrl(serverUrl);

        startAnrWatchdog();
        startHeartbeatWatchdog();
          } catch (Throwable _onCreate_err) {
              try {
                  android.widget.LinearLayout errRoot = new android.widget.LinearLayout(this);
                  errRoot.setOrientation(android.widget.LinearLayout.VERTICAL);
                  errRoot.setGravity(android.view.Gravity.CENTER);
                  errRoot.setBackgroundColor(android.graphics.Color.parseColor("#0a0e1a"));
                  android.widget.TextView errMsg = new android.widget.TextView(this);
                  errMsg.setText("Startup error (" + "ST" + "):\n"
                      + _onCreate_err.getClass().getSimpleName() + ": " + _onCreate_err.getMessage()
                      + "\n\nPlease contact support or reinstall.");
                  errMsg.setTextColor(android.graphics.Color.WHITE);
                  errMsg.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
                  errMsg.setPadding(80, 80, 80, 80);
                  errMsg.setGravity(android.view.Gravity.CENTER);
                  errRoot.addView(errMsg);
                  setContentView(errRoot);
              } catch (Throwable ignored) {}
          }
      }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setDatabaseEnabled(true);
        settings.setTextZoom(100);
        settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(false);
        }

        webView.setBackgroundColor(Color.parseColor("#0a0e1a"));
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

        webView.addJavascriptInterface(new WebAppInterface(), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                errorContainer.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                // Suppress webkit media controls and the native video
                // loading overlay shown during playlist transitions.
                webView.evaluateJavascript(
                    "(function(){" +
                    "var s=document.createElement('style');" +
                    "s.textContent=" +
                    "'video::-webkit-media-controls{display:none!important}" +
                    "video::-webkit-media-controls-enclosure{display:none!important}" +
                    "video::-webkit-media-controls-panel{display:none!important}';" +
                    "document.head&&document.head.appendChild(s);" +
                    "})();",
                    null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError("Connection Lost", "Unable to reach the server. Retrying...");
                    retryConnection();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @android.annotation.TargetApi(Build.VERSION_CODES.O)
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                android.util.Log.w("Digipal", "WebView render process gone; didCrash="
                    + (detail != null && detail.didCrash()));
                if (view != webView) {
                    try { view.destroy(); } catch (Throwable ignored) {}
                    return true;
                }
                recoverFromRenderProcessGone(view);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public View getVideoLoadingProgressView() {
                // Suppress the default grey overlay + play-button that Android
                // WebView renders while a <video> element is buffering or
                // switching sources between playlist items.
                FrameLayout empty = new FrameLayout(MainActivity.this);
                empty.setVisibility(View.GONE);
                return empty;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customViewContainer.addView(view, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ));
                customViewContainer.setVisibility(View.VISIBLE);
                webView.setVisibility(View.GONE);
                hideSystemUI();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                customViewContainer.removeView(customView);
                customView = null;
                customViewContainer.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                hideSystemUI();
            }
        });
    }

    private class WebAppInterface {

        @JavascriptInterface
        public void setAutoRelaunch(boolean enabled) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_AUTO_RELAUNCH, enabled).apply();
            // Restart WatchdogService so the new setting takes effect immediately
            Intent ws = new Intent(MainActivity.this, WatchdogService.class);
            stopService(ws);
            if (enabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(ws);
                else startService(ws);
            }
        }

        @JavascriptInterface
        public void scheduleRelaunch() {
            scheduleAppRelaunch(2000);
        }

        @JavascriptInterface
        public void heartbeat() {
            lastHeartbeatMs = System.currentTimeMillis();
            heartbeatReceived = true;
        }

        @JavascriptInterface
        public String getConnectedDevices() {
            if (hardwareManager == null) return "[]";
            return hardwareManager.getConnectedDevices().toString();
        }

        @JavascriptInterface
        public void startLearnMode() {
            if (hardwareManager != null) {
                hardwareManager.startLearnMode();
            }
        }

        @JavascriptInterface
        public void stopLearnMode() {
            if (hardwareManager != null) {
                hardwareManager.stopLearnMode();
            }
        }

        @JavascriptInterface
        public void startBleScan() {
            if (hardwareManager != null) {
                hardwareManager.startBleScan();
            }
        }

        @JavascriptInterface
        public void downloadMedia(String objectPath, String signedUrl) {
            if (mediaDownloadManager != null) {
                mediaDownloadManager.downloadMedia(objectPath, signedUrl);
            }
        }

        @JavascriptInterface
        public String getLocalMediaPath(String objectPath) {
            if (mediaDownloadManager != null) {
                return mediaDownloadManager.getLocalMediaPath(objectPath);
            }
            return "";
        }

        @JavascriptInterface
        public boolean deleteMedia(String objectPath) {
            if (mediaDownloadManager != null) {
                return mediaDownloadManager.deleteMedia(objectPath);
            }
            return false;
        }

        @JavascriptInterface
        public int deleteAllMedia() {
            if (mediaDownloadManager != null) {
                return mediaDownloadManager.deleteAllMedia();
            }
            return 0;
        }

        @JavascriptInterface
        public String getStorageInfo() {
            if (mediaDownloadManager != null) {
                return mediaDownloadManager.getStorageInfo();
            }
            return "{\"usedBytes\":0,\"freeBytes\":0,\"totalSpace\":0,\"totalFiles\":0}";
        }

        /**
         * Device resource snapshot for the web player to throttle itself before a
         * crash. Method name must stay exactly "getResourceStats" — the player's
         * native bridge depends on it.
         */
        @JavascriptInterface
        public String getResourceStats() {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                if (am != null) {
                    am.getMemoryInfo(mi);
                    o.put("availMemBytes", mi.availMem);
                    o.put("totalMemBytes", mi.totalMem);
                    o.put("lowMemory", mi.lowMemory);
                    o.put("memThresholdBytes", mi.threshold);
                }
                Runtime rt = Runtime.getRuntime();
                o.put("appHeapUsedBytes", rt.totalMemory() - rt.freeMemory());
                o.put("appHeapMaxBytes", rt.maxMemory());
                java.io.File dir = getFilesDir();
                o.put("freeStorageBytes", dir.getUsableSpace());
                o.put("totalStorageBytes", dir.getTotalSpace());
                o.put("uptimeMs", android.os.SystemClock.elapsedRealtime());
                o.put("sdkInt", Build.VERSION.SDK_INT);
                return o.toString();
            } catch (Throwable e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void notifyPaired(String url) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            if (prefs.getBoolean("cloud_pairing_pending", false)) {
                String serverUrl = (url != null && !url.isEmpty()) ? url : BuildConfig.SERVER_URL;
                prefs.edit()
                    .putString("server_mode", "cloud")
                    .putString(KEY_SERVER_URL, serverUrl)
                    .putBoolean("cloud_pairing_pending", false)
                    .apply();
            }
        }

          @JavascriptInterface
          public void captureScreenshot(String requestId) {
              if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                  // PixelCopy requires API 26+; signal null so the web side falls back to html2canvas
                  runOnUiThread(() ->
                      webView.evaluateJavascript(
                          "if(window.__digipalNativeScreenshot)window.__digipalNativeScreenshot('"
                              + requestId + "',null)", null));
                  return;
              }
              Bitmap bmp = Bitmap.createBitmap(
                      webView.getWidth(), webView.getHeight(), Bitmap.Config.ARGB_8888);
              PixelCopy.request(
                      getWindow(),
                      new Rect(0, 0, webView.getWidth(), webView.getHeight()),
                      bmp,
                      copyResult -> {
                          String b64 = null;
                          if (copyResult == PixelCopy.SUCCESS) {
                              ByteArrayOutputStream baos = new ByteArrayOutputStream();
                              bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos);
                              b64 = "data:image/jpeg;base64,"
                                      + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                          }
                          final String payload = b64;
                          runOnUiThread(() ->
                              webView.evaluateJavascript(
                                  "if(window.__digipalNativeScreenshot)window.__digipalNativeScreenshot('"
                                      + requestId + "'," + (payload == null ? "null" : "'" + payload + "'") + ")",
                                  null));
                      },
                      new Handler(Looper.getMainLooper()));
          }
    }

    private void scheduleAppRelaunch(long delayMs) {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            int flags = PendingIntent.FLAG_ONE_SHOT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, flags
            );

            AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (alarmManager != null) {
                long triggerAt = System.currentTimeMillis() + delayMs;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
                }
            }
        } catch (SecurityException e) {
            try {
                Intent intent = new Intent(this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                int flags = PendingIntent.FLAG_ONE_SHOT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    flags |= PendingIntent.FLAG_IMMUTABLE;
                }
                PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, flags);
                AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
                if (alarmManager != null) {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pendingIntent);
                }
            } catch (Throwable ignored) {}
        }
    }

    private boolean isAutoRelaunchEnabled() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(KEY_AUTO_RELAUNCH, false);
    }

    private String getServerUrl() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String saved = prefs.getString(KEY_SERVER_URL, null);
        if (saved != null && !saved.isEmpty()) {
            return saved;
        }
        return BuildConfig.SERVER_URL;
    }

    private void loadPlayerUrl(String baseUrl) {
        if (baseUrl.startsWith("http://")) {
            try {
                String host = new java.net.URI(baseUrl).getHost();
                if (host == null || !PRIVATE_IP_PATTERN.matcher(host).matches()) {
                    showError("Security Error", "HTTP connections are only allowed to local network servers. Use https:// for public servers.");
                    return;
                }
            } catch (Throwable e) {
                showError("Invalid URL", "Could not parse server address.");
                return;
            }
        }
        String playerUrl = baseUrl;
        if (!playerUrl.endsWith("/")) {
            playerUrl += "/";
        }
        playerUrl += "player";
        webView.loadUrl(playerUrl);
    }

    private void showError(String title, String message) {
        errorContainer.removeAllViews();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(android.view.Gravity.CENTER);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.parseColor("#ef4444"));
        titleView.setTextSize(28);
        titleView.setGravity(android.view.Gravity.CENTER);
        layout.addView(titleView);

        TextView msgView = new TextView(this);
        msgView.setText(message);
        msgView.setTextColor(Color.parseColor("#94a3b8"));
        msgView.setTextSize(16);
        msgView.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = 24;
        msgView.setLayoutParams(params);
        layout.addView(msgView);

        errorContainer.addView(layout, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        errorContainer.setVisibility(View.VISIBLE);
    }

    private void retryConnection() {
        webView.postDelayed(() -> {
            String serverUrl = getServerUrl();
            loadPlayerUrl(serverUrl);
        }, 5000);
    }

    // ---- Crash resilience: renderer recovery, memory pressure, ANR watchdog ----

    private final long[] renderGoneTimestamps = new long[3];
    private int renderGoneIdx = 0;
    private long lastMemoryReloadMs = 0L;
    // --- Self-heal watchdog (heartbeat-based WebView reload) ----------------
    // The web player calls Android.heartbeat() from its JS event loop every few
    // seconds. This main-thread Handler keeps ticking even when the WebView JS
    // freezes (e.g. page wedged "Offline" after a brief server loss), so if
    // heartbeats stop while the network is still up we reload the WebView once
    // (with back-off) to un-wedge it. Complements WatchdogService (dead process)
    // and the ANR watchdog (hung UI thread).
    private volatile long lastHeartbeatMs = 0;
    private volatile boolean heartbeatReceived = false;
    private long lastWatchdogReloadMs = 0;
    private android.os.Handler heartbeatWatchdogHandler;
    private Runnable heartbeatWatchdogRunnable;
    private static final long HEARTBEAT_STALE_MS = 90000;
    private static final long WATCHDOG_CHECK_INTERVAL_MS = 15000;
    private static final long WATCHDOG_RELOAD_BACKOFF_MS = 120000;
    private Thread anrWatchdogThread;
    private volatile boolean anrWatchdogRunning = false;
    private final android.os.Handler anrMainHandler =
        new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * Called when the WebView renderer process is killed (OOM or GPU driver crash).
     * Rebuilds a fresh WebView in place and reloads the player so the screen
     * recovers on its own instead of dropping to the launcher. Falls back to a
     * full activity relaunch if the renderer keeps dying (crash loop).
     */
    private void recoverFromRenderProcessGone(WebView deadView) {
        long now = System.currentTimeMillis();
        renderGoneTimestamps[renderGoneIdx % renderGoneTimestamps.length] = now;
        renderGoneIdx++;
        if (renderGoneIdx >= renderGoneTimestamps.length) {
            long oldest = Long.MAX_VALUE;
            for (long t : renderGoneTimestamps) { if (t > 0 && t < oldest) oldest = t; }
            if (now - oldest < 60_000L) {
                // Renderer is crash-looping — relaunch the whole activity with backoff.
                if (isAutoRelaunchEnabled()) scheduleAppRelaunch(5000);
                isUserClosing = true;
                finish();
                return;
            }
        }
        try {
            if (deadView != null && rootLayout != null) {
                try { rootLayout.removeView(deadView); } catch (Throwable ignored) {}
            }
            try { if (deadView != null) deadView.destroy(); } catch (Throwable ignored) {}

            webView = new WebView(this);
            setupWebView();
            webView.setFocusableInTouchMode(true);
            webView.requestFocus();
            if (rootLayout != null) {
                rootLayout.addView(webView, 0, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            }
            if (mediaDownloadManager != null) mediaDownloadManager.setWebView(webView);
            loadPlayerUrl(getServerUrl());
        } catch (Throwable e) {
            if (isAutoRelaunchEnabled()) scheduleAppRelaunch(3000);
            isUserClosing = true;
            finish();
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        handleMemoryPressure(level);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        handleMemoryPressure(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
    }

    /**
     * React to Android memory-pressure callbacks. On moderate pressure we ask the
     * web player to drop caches; on critical pressure we clear the WebView cache
     * and schedule a clean reload (debounced) so the OS does not kill us first.
     */
    private void handleMemoryPressure(int level) {
        try {
            if (webView == null) return;
            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                    || level == android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
                webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('android-memory-pressure',{detail:{level:'critical'}}));",
                    null);
                try { webView.clearCache(false); } catch (Throwable ignored) {}
                long now = System.currentTimeMillis();
                if (now - lastMemoryReloadMs > 120_000L) {
                    lastMemoryReloadMs = now;
                    // Give the player a few seconds to recover gracefully, then
                    // reload natively as a safety net if we are still alive.
                    webView.postDelayed(() -> {
                        try { loadPlayerUrl(getServerUrl()); } catch (Throwable ignored) {}
                    }, 4000);
                }
            } else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('android-memory-pressure',{detail:{level:'moderate'}}));",
                    null);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Lightweight ANR watchdog: posts a heartbeat to the main-thread Handler every
     * few seconds. If the UI thread fails to process several consecutive
     * heartbeats it is hung, so we relaunch and kill the stuck process.
     */
    private void startAnrWatchdog() {
        if (anrWatchdogThread != null) return;
        anrWatchdogRunning = true;
        anrWatchdogThread = new Thread(() -> {
            int missed = 0;
            while (anrWatchdogRunning) {
                final java.util.concurrent.atomic.AtomicBoolean ticked =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
                try { anrMainHandler.post(() -> ticked.set(true)); } catch (Throwable ignored) {}
                try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
                if (ticked.get()) {
                    missed = 0;
                } else {
                    missed++;
                    if (missed >= 3 && activityVisible && isAutoRelaunchEnabled()) {
                        // Main thread hung ~15s — schedule relaunch then kill the stuck process.
                        scheduleAppRelaunch(2000);
                        android.os.Process.killProcess(android.os.Process.myPid());
                        return;
                    }
                }
            }
        }, "digipal-anr-watchdog");
        anrWatchdogThread.setDaemon(true);
        anrWatchdogThread.start();
    }

    private void stopAnrWatchdog() {
        anrWatchdogRunning = false;
        if (anrWatchdogThread != null) {
            try { anrWatchdogThread.interrupt(); } catch (Throwable ignored) {}
            anrWatchdogThread = null;
        }
    }

    // Reloads the WebView when the web player stops heart-beating (frozen JS)
    // while the device still has a network. Armed only after the first
    // heartbeat, so the setup screen and cold start are never disturbed.
    private void startHeartbeatWatchdog() {
        heartbeatWatchdogHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        heartbeatWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    if (heartbeatReceived) {
                        long now = System.currentTimeMillis();
                        if (now - lastHeartbeatMs > HEARTBEAT_STALE_MS
                                && isNetworkConnectedForWatchdog()
                                && now - lastWatchdogReloadMs > WATCHDOG_RELOAD_BACKOFF_MS) {
                            lastWatchdogReloadMs = now;
                            lastHeartbeatMs = now; // avoid immediate re-fire
                            android.util.Log.w("DigipalWatchdog",
                                "Player heartbeat stale with network up - reloading WebView");
                            forcePlayerReload();
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (heartbeatWatchdogHandler != null) {
                        heartbeatWatchdogHandler.postDelayed(this, WATCHDOG_CHECK_INTERVAL_MS);
                    }
                }
            }
        };
        heartbeatWatchdogHandler.postDelayed(heartbeatWatchdogRunnable, WATCHDOG_CHECK_INTERVAL_MS);
    }

    private void stopHeartbeatWatchdog() {
        if (heartbeatWatchdogHandler != null) {
            heartbeatWatchdogHandler.removeCallbacksAndMessages(null);
            heartbeatWatchdogHandler = null;
        }
    }

    private void forcePlayerReload() {
        runOnUiThread(() -> {
            try {
                if (errorContainer != null) errorContainer.setVisibility(View.GONE);
                String url = webView != null ? webView.getUrl() : null;
                if (webView != null && url != null && !url.startsWith("about:")) {
                    webView.reload();
                } else {
                    loadPlayerUrl(getServerUrl());
                }
            } catch (Exception e) {
                try { loadPlayerUrl(getServerUrl()); } catch (Exception ignored) {}
            }
        });
    }

    private boolean isNetworkConnectedForWatchdog() {
        try {
            android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.net.Network n = cm.getActiveNetwork();
                if (n == null) return false;
                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                return caps != null && caps.hasCapability(
                    android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } else {
                android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                return ni != null && ni.isConnected();
            }
        } catch (Exception e) {
            return true; // fail-open: do not suppress self-heal if network is unknown
        }
    }

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
          if (keyCode == KeyEvent.KEYCODE_HOME
                  || keyCode == KeyEvent.KEYCODE_APP_SWITCH
                  || keyCode == KeyEvent.KEYCODE_MENU
                  || keyCode == KeyEvent.KEYCODE_BACK) {
              return true;
          }
          if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                  || keyCode == KeyEvent.KEYCODE_ENTER
                  || keyCode == KeyEvent.KEYCODE_BUTTON_A
                  || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
              long now = System.currentTimeMillis();
              if (now - dpadFirstPressMs > 5_000L) { dpadPressCount = 0; dpadFirstPressMs = now; }
              dpadPressCount++;
              if (dpadPressCount >= 7) {
                  dpadPressCount = 0;
                  if (diagnosticsOverlay != null) hideDiagnosticsOverlay();
                  else showDiagnosticsOverlay();
                  return true;
              }
          }
          if (webView != null) webView.dispatchKeyEvent(event);
          return true;
      }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityVisible = true;
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(24 * 60 * 60 * 1000L);
        }
        hideSystemUI();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        webView.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        activityVisible = false;
    }

    @Override
    protected void onDestroy() {
        stopAnrWatchdog();
        stopHeartbeatWatchdog();
        if (hardwareManager != null) {
            hardwareManager.stop();
        }
        if (isAutoRelaunchEnabled()) {
            scheduleAppRelaunch(3000);
        }
        if (webView != null) {
            webView.destroy();
        }
        activityAlive = false;
        super.onDestroy();
    }

    private void requestCameraPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, PERMISSION_REQUEST_CAMERA);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
      @Override
      public boolean dispatchKeyEvent(KeyEvent event) {
          if (webView != null && event.getAction() == KeyEvent.ACTION_DOWN) {
              int k = event.getKeyCode();
              if (k != KeyEvent.KEYCODE_BACK && k != KeyEvent.KEYCODE_HOME
                      && k != KeyEvent.KEYCODE_APP_SWITCH && k != KeyEvent.KEYCODE_MENU) {
                  webView.dispatchKeyEvent(event);
              }
          }
          return super.dispatchKeyEvent(event);
      }

      private void showDiagnosticsOverlay() {
          if (diagnosticsOverlay != null) return;
          try {
              android.widget.FrameLayout root = (android.widget.FrameLayout) getWindow().getDecorView();
              android.widget.TextView diagTv = new android.widget.TextView(this);
              diagTv.setBackgroundColor(0xCC000000);
              diagTv.setTextColor(0xFFFFFFFF);
              diagTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
              diagTv.setPadding(60, 60, 60, 60);
              diagTv.setGravity(android.view.Gravity.CENTER);
              String ip = "unavailable";
              try {
                  WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                  if (wm != null) ip = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
              } catch (Throwable ignored) {}
              long uptimeSec = android.os.SystemClock.elapsedRealtime() / 1000;
              String uptime = String.format("%dh %02dm %02ds", uptimeSec / 3600, (uptimeSec % 3600) / 60, uptimeSec % 60);
              String ver = "?";
              try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable ignored) {}
              diagTv.setText("DIGIPAL DIAGNOSTICS\n\nPackage: " + getPackageName()
                      + "\nVersion:  " + ver
                      + "\nIP:       " + ip
                      + "\nUptime:   " + uptime
                      + "\n\nPress SELECT again or wait 10s to dismiss");
              android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                      android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                      android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
              root.addView(diagTv, lp);
              diagnosticsOverlay = diagTv;
              diagDismissHandler.postDelayed(this::hideDiagnosticsOverlay, 10_000L);
          } catch (Throwable e) {
              android.util.Log.e("Digipal", "showDiagnosticsOverlay failed", e);
          }
      }

      private void hideDiagnosticsOverlay() {
          try {
              diagDismissHandler.removeCallbacksAndMessages(null);
              if (diagnosticsOverlay != null) {
                  android.widget.FrameLayout root = (android.widget.FrameLayout) getWindow().getDecorView();
                  root.removeView(diagnosticsOverlay);
                  diagnosticsOverlay = null;
              }
          } catch (Throwable ignored) {}
      }
  
}