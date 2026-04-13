package com.nexuscast.launcher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.provider.Settings;
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
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private WebView webView;
    private FrameLayout rootLayout;
    private FrameLayout errorContainer;
    private FrameLayout diagnosticsContainer;
    private PowerManager.WakeLock wakeLock;
    private Handler retryHandler;
    private Runnable retryRunnable;
    private ConnectivityManager.NetworkCallback networkCallback;

    private static final String PREFS_NAME = "DigipalLauncherPrefs";
    private static final String KEY_AUTO_RELAUNCH = "auto_relaunch";
    private MediaDownloadManager mediaDownloadManager;

    private static final int RAPID_TAP_COUNT = 5;
    private static final long RAPID_TAP_WINDOW_MS = 2000;
    private long[] tapTimestamps = new long[RAPID_TAP_COUNT];
    private int tapIndex = 0;

    private static final int DPAD_DIAG_COUNT = 5;
    private static final long DPAD_DIAG_WINDOW_MS = 3000;
    private long[] dpadUpTimestamps = new long[DPAD_DIAG_COUNT];
    private int dpadUpIndex = 0;

    private boolean isNetworkAvailable = true;
    private boolean isDiagnosticsVisible = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
            getWindow().setSustainedPerformanceMode(true);
        }

        hideSystemUI();

        retryHandler = new Handler(Looper.getMainLooper());

        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.parseColor("#0a0e1a"));

        webView = new WebView(this);
        setupWebView();

        rootLayout.addView(webView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        errorContainer = new FrameLayout(this);
        errorContainer.setBackgroundColor(Color.parseColor("#0a0e1a"));
        errorContainer.setVisibility(View.GONE);
        rootLayout.addView(errorContainer, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        diagnosticsContainer = new FrameLayout(this);
        diagnosticsContainer.setBackgroundColor(Color.parseColor("#cc0a0e1a"));
        diagnosticsContainer.setVisibility(View.GONE);
        rootLayout.addView(diagnosticsContainer, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        setContentView(rootLayout);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "nexuscast:launcher_wakelock"
        );

        mediaDownloadManager = new MediaDownloadManager(this);
        mediaDownloadManager.setWebView(webView);
        mediaDownloadManager.cleanupOrphans();

        setupNetworkMonitor();
        startWatchdogService();
        loadPlayer();
    }

    private void startWatchdogService() {
        Intent serviceIntent = new Intent(this, WatchdogService.class);
        startService(serviceIntent);
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
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                return true;
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
    }

    private void loadPlayer() {
        if (!isNetworkConnected()) {
            showError("No Network", "Waiting for network connection...");
            retryConnection();
            return;
        }
        errorContainer.setVisibility(View.GONE);
        webView.loadUrl(BuildConfig.PLAYER_URL);
    }

    private boolean isNetworkConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
        } else {
            android.net.NetworkInfo netInfo = cm.getActiveNetworkInfo();
            return netInfo != null && netInfo.isConnected();
        }
    }

    private String getNetworkType() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return "Unknown";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network == null) return "None";
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) return "None";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WiFi";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "Cellular";
            return "Other";
        } else {
            android.net.NetworkInfo netInfo = cm.getActiveNetworkInfo();
            if (netInfo == null || !netInfo.isConnected()) return "None";
            return netInfo.getTypeName();
        }
    }

    @SuppressLint("NewApi")
    private void setupNetworkMonitor() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    if (!isNetworkAvailable) {
                        isNetworkAvailable = true;
                        runOnUiThread(() -> loadPlayer());
                    }
                }

                @Override
                public void onLost(Network network) {
                    isNetworkAvailable = false;
                    runOnUiThread(() -> {
                        showError("Network Lost", "Waiting for network connection...");
                        retryConnection();
                    });
                }
            };

            NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
            cm.registerNetworkCallback(request, networkCallback);
        }
    }

    private void showError(String title, String message) {
        errorContainer.removeAllViews();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.parseColor("#ef4444"));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        titleView.setGravity(Gravity.CENTER);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        layout.addView(titleView);

        TextView msgView = new TextView(this);
        msgView.setText(message);
        msgView.setTextColor(Color.parseColor("#94a3b8"));
        msgView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        msgView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = 24;
        msgView.setLayoutParams(params);
        layout.addView(msgView);

        TextView wifiBtn = new TextView(this);
        wifiBtn.setText("  WiFi Settings  ");
        wifiBtn.setTextColor(Color.WHITE);
        wifiBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        wifiBtn.setBackgroundColor(Color.parseColor("#3b82f6"));
        wifiBtn.setPadding(40, 20, 40, 20);
        wifiBtn.setGravity(Gravity.CENTER);
        wifiBtn.setFocusable(true);
        wifiBtn.setFocusableInTouchMode(true);
        LinearLayout.LayoutParams wifiBtnParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        wifiBtnParams.gravity = Gravity.CENTER;
        wifiBtnParams.topMargin = 40;
        wifiBtn.setLayoutParams(wifiBtnParams);
        wifiBtn.setOnClickListener(v -> openWifiSettings());
        layout.addView(wifiBtn);

        errorContainer.addView(layout, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        errorContainer.setVisibility(View.VISIBLE);
    }

    private void retryConnection() {
        if (retryRunnable != null) {
            retryHandler.removeCallbacks(retryRunnable);
        }
        retryRunnable = () -> loadPlayer();
        retryHandler.postDelayed(retryRunnable, 5000);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            float x = event.getX();
            float y = event.getY();

            int screenWidth = getWindow().getDecorView().getWidth();
            int tapZoneSize = (int) (screenWidth * 0.1);

            if (x > screenWidth - tapZoneSize && y < tapZoneSize) {
                long now = System.currentTimeMillis();
                tapTimestamps[tapIndex % RAPID_TAP_COUNT] = now;
                tapIndex++;

                if (tapIndex >= RAPID_TAP_COUNT) {
                    long oldest = tapTimestamps[(tapIndex) % RAPID_TAP_COUNT];
                    if (now - oldest <= RAPID_TAP_WINDOW_MS) {
                        toggleDiagnostics();
                        tapIndex = 0;
                        return true;
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private void toggleDiagnostics() {
        if (isDiagnosticsVisible) {
            diagnosticsContainer.setVisibility(View.GONE);
            isDiagnosticsVisible = false;
        } else {
            showDiagnostics();
            isDiagnosticsVisible = true;
        }
    }

    @SuppressLint("SetTextI18n")
    private void showDiagnostics() {
        diagnosticsContainer.removeAllViews();

        ScrollView scrollView = new ScrollView(this);
        scrollView.setPadding(60, 60, 60, 60);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.parseColor("#1a1e2e"));
        layout.setPadding(40, 40, 40, 40);

        TextView header = new TextView(this);
        header.setText("Diagnostics");
        header.setTextColor(Color.parseColor("#2aabb3"));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setGravity(Gravity.CENTER);
        layout.addView(header);

        addDiagLine(layout, "Browser Engine", "Android WebView");
        addDiagLine(layout, "Android Version", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        addDiagLine(layout, "Device Model", Build.MANUFACTURER + " " + Build.MODEL);
        addDiagLine(layout, "RAM", getMemoryInfo());
        addDiagLine(layout, "GPU", getGpuInfo());
        addDiagLine(layout, "Network", getNetworkType());
        addDiagLine(layout, "Player URL", BuildConfig.PLAYER_URL);
        addDiagLine(layout, "App Version", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowParams.topMargin = 40;
        buttonRow.setLayoutParams(rowParams);

        TextView wifiBtn = new TextView(this);
        wifiBtn.setText("  WiFi Settings  ");
        wifiBtn.setTextColor(Color.WHITE);
        wifiBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        wifiBtn.setBackgroundColor(Color.parseColor("#3b82f6"));
        wifiBtn.setPadding(40, 20, 40, 20);
        wifiBtn.setGravity(Gravity.CENTER);
        wifiBtn.setFocusable(true);
        wifiBtn.setFocusableInTouchMode(true);
        wifiBtn.setOnClickListener(v -> openWifiSettings());
        buttonRow.addView(wifiBtn);

        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(40, 0);
        View spacer = new View(this);
        spacer.setLayoutParams(spacerParams);
        buttonRow.addView(spacer);

        TextView closeBtn = new TextView(this);
        closeBtn.setText("  Close  ");
        closeBtn.setTextColor(Color.WHITE);
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        closeBtn.setBackgroundColor(Color.parseColor("#2aabb3"));
        closeBtn.setPadding(40, 20, 40, 20);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setFocusable(true);
        closeBtn.setFocusableInTouchMode(true);
        closeBtn.setOnClickListener(v -> {
            diagnosticsContainer.setVisibility(View.GONE);
            isDiagnosticsVisible = false;
        });
        buttonRow.addView(closeBtn);

        layout.addView(buttonRow);

        scrollView.addView(layout);
        diagnosticsContainer.addView(scrollView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        diagnosticsContainer.setVisibility(View.VISIBLE);
    }

    private void openWifiSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(fallback);
            } catch (Exception ignored) {
            }
        }
    }

    private void addDiagLine(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 16, 0, 16);

        TextView labelView = new TextView(this);
        labelView.setText(label + ": ");
        labelView.setTextColor(Color.parseColor("#94a3b8"));
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(labelView);

        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextColor(Color.WHITE);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        row.addView(valueView);

        parent.addView(row);
    }

    private String getMemoryInfo() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(memInfo);
        long availMB = memInfo.availMem / (1024 * 1024);
        long totalMB = memInfo.totalMem / (1024 * 1024);
        return availMB + " MB available / " + totalMB + " MB total";
    }

    private String getGpuInfo() {
        try {
            String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
            String vendor = GLES20.glGetString(GLES20.GL_VENDOR);
            if (renderer != null && vendor != null) {
                return vendor + " " + renderer;
            }
        } catch (Exception e) {
        }
        return "Not available (requires GL context)";
    }

    private void scheduleAppRelaunch(long delayMs) {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            int flags = PendingIntent.FLAG_ONE_SHOT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, flags);

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
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                int flags = PendingIntent.FLAG_ONE_SHOT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    flags |= PendingIntent.FLAG_IMMUTABLE;
                }
                PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, flags);
                AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
                if (alarmManager != null) {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pendingIntent);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private boolean isAutoRelaunchEnabled() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(KEY_AUTO_RELAUNCH, true);
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
        if (keyCode == KeyEvent.KEYCODE_BACK ||
            keyCode == KeyEvent.KEYCODE_HOME ||
            keyCode == KeyEvent.KEYCODE_APP_SWITCH ||
            keyCode == KeyEvent.KEYCODE_MENU) {
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            long now = System.currentTimeMillis();
            dpadUpTimestamps[dpadUpIndex % DPAD_DIAG_COUNT] = now;
            dpadUpIndex++;
            if (dpadUpIndex >= DPAD_DIAG_COUNT) {
                long oldest = dpadUpTimestamps[dpadUpIndex % DPAD_DIAG_COUNT];
                if (now - oldest <= DPAD_DIAG_WINDOW_MS) {
                    toggleDiagnostics();
                    dpadUpIndex = 0;
                }
            }
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
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
    protected void onDestroy() {
        if (retryHandler != null && retryRunnable != null) {
            retryHandler.removeCallbacks(retryRunnable);
        }

        if (networkCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.unregisterNetworkCallback(networkCallback);
                }
            } catch (Exception ignored) {
            }
        }

        if (isAutoRelaunchEnabled()) {
            scheduleAppRelaunch(3000);
        }

        if (webView != null) {
            webView.destroy();
        }

        super.onDestroy();
    }
}
