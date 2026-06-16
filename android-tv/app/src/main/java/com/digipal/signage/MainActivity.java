package com.digipal.signage;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.net.wifi.WifiManager;
  import android.text.format.Formatter;
  public class MainActivity extends Activity {

    /** Set true in onResume, false in onStop — read by WatchdogService.inForeground(). */
    public static volatile boolean activityVisible = false;
    /** Set true in onCreate, false in onDestroy — used by WatchdogService to detect real crashes vs Home-press. */
    public static volatile boolean activityAlive = false;

    private WebView webView;
    private FrameLayout errorContainer;
    private PowerManager.WakeLock wakeLock;
    private MediaDownloadManager mediaDownloadManager;
    private static final String PREFS_NAME = "DigipalPrefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_SERVER_MODE = "server_mode";
    private static final String KEY_AUTO_RELAUNCH = "auto_relaunch";
    private boolean isUserClosing = false;
    private View customView;
    private FrameLayout customViewContainer;
    private boolean hasHttpError = false;
      private int dpadPressCount = 0;
      private long dpadFirstPressMs = 0L;
      private android.view.View diagnosticsOverlay = null;
      private final android.os.Handler diagDismissHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private static final java.util.regex.Pattern PRIVATE_IP_PATTERN = java.util.regex.Pattern.compile(
        "^(10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}" +
        "|172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}" +
        "|192\\.168\\.\\d{1,3}\\.\\d{1,3}" +
        "|127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}" +
        "|localhost" +
        "|\\[::1\\])$"
    );

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
            "digipal:wakelock"
        );

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
          } catch (Throwable _onCreate_err) {
              try {
                  android.widget.LinearLayout errRoot = new android.widget.LinearLayout(this);
                  errRoot.setOrientation(android.widget.LinearLayout.VERTICAL);
                  errRoot.setGravity(android.view.Gravity.CENTER);
                  errRoot.setBackgroundColor(android.graphics.Color.parseColor("#0a0e1a"));
                  android.widget.TextView errMsg = new android.widget.TextView(this);
                  errMsg.setText("Startup error (" + "TV" + "):\n"
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
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                hasHttpError = false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!hasHttpError) {
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
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    hasHttpError = true;
                    showError("Connection Lost", "Unable to reach the server. Retrying...");
                    retryConnection();
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse errorResponse) {
                if (request.isForMainFrame()) {
                    int statusCode = errorResponse.getStatusCode();
                    if (statusCode >= 500) {
                        hasHttpError = true;
                        showError("Connecting...", "Server is starting up. Retrying...");
                        retryConnection();
                    }
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
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
        }

        @JavascriptInterface
        public void scheduleRelaunch() {
            scheduleAppRelaunch(2000);
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

        @JavascriptInterface
        public void openServerSettings() {
            runOnUiThread(() -> openSetupScreen());
        }

        @JavascriptInterface
        public String getServerMode() {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            return prefs.getString(KEY_SERVER_MODE, "cloud");
        }

        @JavascriptInterface
        public String getConnectedServerUrl() {
            return getServerUrl();
        }


          @JavascriptInterface
          public String scanLocalServers() {
              android.net.nsd.NsdManager nsdManager =
                  (android.net.nsd.NsdManager) getSystemService(android.content.Context.NSD_SERVICE);
              if (nsdManager == null) return "[]";

              final java.util.List<String> found =
                  java.util.Collections.synchronizedList(new java.util.ArrayList<>());
              final java.util.concurrent.atomic.AtomicInteger pending =
                  new java.util.concurrent.atomic.AtomicInteger(0);

              final java.util.concurrent.CountDownLatch stopLatch =
                  new java.util.concurrent.CountDownLatch(1);
              final java.util.concurrent.CountDownLatch resolveLatch =
                  new java.util.concurrent.CountDownLatch(1);

              android.net.nsd.NsdManager.DiscoveryListener discoveryListener =
                  new android.net.nsd.NsdManager.DiscoveryListener() {
                      @Override public void onStartDiscoveryFailed(String t, int e) { stopLatch.countDown(); }
                      @Override public void onStopDiscoveryFailed(String t, int e) {}
                      @Override public void onDiscoveryStarted(String t) {}
                      @Override public void onDiscoveryStopped(String t) { stopLatch.countDown(); }
                      @Override public void onServiceLost(android.net.nsd.NsdServiceInfo si) {}
                      @Override
                      public void onServiceFound(android.net.nsd.NsdServiceInfo serviceInfo) {
                          pending.incrementAndGet();
                          nsdManager.resolveService(serviceInfo,
                              new android.net.nsd.NsdManager.ResolveListener() {
                                  @Override public void onResolveFailed(android.net.nsd.NsdServiceInfo si, int err) {
                                      if (pending.decrementAndGet() == 0) resolveLatch.countDown();
                                  }
                                  @Override
                                  public void onServiceResolved(android.net.nsd.NsdServiceInfo si) {
                                      try {
                                          java.net.InetAddress host = si.getHost();
                                          if (host != null) {
                                              found.add("http://" + host.getHostAddress() + ":" + si.getPort());
                                          }
                                      } catch (Throwable ignored) {
                                      } finally {
                                          if (pending.decrementAndGet() == 0) resolveLatch.countDown();
                                      }
                                  }
                              });
                      }
                  };

              try {
                  nsdManager.discoverServices("_digipal._tcp.",
                      android.net.nsd.NsdManager.PROTOCOL_DNS_SD, discoveryListener);
                  new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                      try { nsdManager.stopServiceDiscovery(discoveryListener); } catch (Throwable ignored) {}
                  }, 3000);
                  stopLatch.await(4000, java.util.concurrent.TimeUnit.MILLISECONDS);
                  if (pending.get() > 0) {
                      resolveLatch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
                  }
              } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
              } catch (Throwable ignored) {}

              org.json.JSONArray arr = new org.json.JSONArray();
              for (String url : found) arr.put(url);
              return arr.toString();
          }

          @JavascriptInterface
        public void notifyPaired(String url) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            if (prefs.getBoolean("cloud_pairing_pending", false)) {
                String serverUrl = (url != null && !url.isEmpty()) ? url : BuildConfig.SERVER_URL;
                prefs.edit()
                    .putString(KEY_SERVER_MODE, "cloud")
                    .putString(KEY_SERVER_URL, serverUrl)
                    .putBoolean("cloud_pairing_pending", false)
                    .apply();
            }
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
            // Fallback: try non-exact alarm if exact alarm permission not granted
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

    private void openSetupScreen() {
        isUserClosing = true;
        Intent intent = new Intent(this, ServerSetupActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
          if (keyCode == KeyEvent.KEYCODE_HOME
                  || keyCode == KeyEvent.KEYCODE_APP_SWITCH
                  || keyCode == KeyEvent.KEYCODE_MENU) {
              return true;
          }
          if (keyCode == KeyEvent.KEYCODE_BACK) {
              // Back is a no-op in kiosk/signage mode — swallow to prevent accidental navigation.
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
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        hideSystemUI();
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
        if (isAutoRelaunchEnabled() && !isUserClosing) {
            scheduleAppRelaunch(3000);
        }
        if (webView != null) {
            webView.destroy();
        }
        activityAlive = false;
        super.onDestroy();
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
          diagDismissHandler.removeCallbacksAndMessages(null);
          if (diagnosticsOverlay != null) {
              try {
                  android.widget.FrameLayout root = (android.widget.FrameLayout) getWindow().getDecorView();
                  root.removeView(diagnosticsOverlay);
              } catch (Throwable ignored) {}
              diagnosticsOverlay = null;
          }
      }
  
}