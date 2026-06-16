package com.digipal.player;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
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
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Main activity for the Digipal Android TV Player.
 *
 * Key design choices:
 *  - WebChromeClient implements onShowCustomView / onHideCustomView so that
 *    <video> fullscreen requests stay inside the WebView instead of being
 *    handed off to the system native video player (which shows the circular
 *    play-button overlay and ignores the landscape layout).
 *  - WatchdogService is started in onCreate() for crash recovery.
 *  - onDestroy() schedules an app relaunch via BootLaunchService (works on
 *    Android 12+ unlike PendingIntent.getActivity()).
 *  - D-pad / remote: BACK/HOME/MENU/APP_SWITCH are blocked; 7 rapid presses
 *    of OK/Select opens a diagnostics overlay.
 */
public class MainActivity extends Activity {

    private static final String PREFS_NAME        = "DigipalPlayerPrefs";
    private static final String KEY_AUTO_RELAUNCH  = "auto_relaunch";
    private static final String KEY_SERVER_URL     = "server_url";

    // ── D-pad diagnostics ─────────────────────────────────────────────────────
    private static final int  DIAG_TAP_COUNT    = 7;
    private static final long DIAG_TAP_WINDOW_MS = 3_000L;
    private int  diagSelectCount   = 0;
    private long diagFirstSelectMs = 0L;
    private View diagOverlay       = null;

    private WebView      webView;
    private FrameLayout  rootLayout;
    private FrameLayout  customViewContainer;
    private View         currentCustomView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private PowerManager.WakeLock wakeLock;
    private Handler retryHandler;
    private Runnable retryRunnable;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

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

        hideSystemUI();

        retryHandler = new Handler(Looper.getMainLooper());

        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.BLACK);

        webView = new WebView(this);
        setupWebView();

        rootLayout.addView(webView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // Direct D-pad events to the WebView immediately.
        webView.requestFocus();

        // Full-screen overlay container used by onShowCustomView for inline video.
        customViewContainer = new FrameLayout(this);
        customViewContainer.setBackgroundColor(Color.BLACK);
        customViewContainer.setVisibility(View.GONE);
        rootLayout.addView(customViewContainer, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        setContentView(rootLayout);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "digipal:player_wakelock"
            );
        }

        startWatchdogService();
        loadPlayer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        if (webView != null) {
            webView.onResume();
            webView.requestFocus();
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            try { wakeLock.acquire(10 * 60 * 60 * 1000L); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onDestroy() {
        if (retryHandler != null && retryRunnable != null) {
            retryHandler.removeCallbacks(retryRunnable);
        }
        if (isAutoRelaunchEnabled()) {
            scheduleAppRelaunch(3000);
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    // -------------------------------------------------------------------------
    // D-pad / remote control
    // -------------------------------------------------------------------------

    /**
     * Block navigation-breaking keys (Back, Home, Menu, App-Switch) so users
     * cannot accidentally exit the kiosk player. Track rapid OK/Select presses
     * to open the diagnostics overlay (7 presses within 3 seconds).
     *
     * All other keys (D-pad directions, volume, etc.) are passed to super so
     * the WebView receives them normally.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            // ── Block exit keys ───────────────────────────────────────────────
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_MENU:
                return true; // consumed — do not propagate

            // ── Diagnostics shortcut (7× rapid confirm) ───────────────────────
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A: {
                long now = System.currentTimeMillis();
                if (diagSelectCount == 0 || (now - diagFirstSelectMs) > DIAG_TAP_WINDOW_MS) {
                    diagSelectCount   = 1;
                    diagFirstSelectMs = now;
                } else {
                    diagSelectCount++;
                }
                if (diagSelectCount >= DIAG_TAP_COUNT) {
                    diagSelectCount = 0;
                    toggleDiagnostics();
                    return true;
                }
                // Fall through so the WebView also gets the key event.
                break;
            }

            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * Forward key events to the WebView at dispatch level.
     * Some Samsung and LG remotes deliver events here rather than onKeyDown.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (webView != null && diagOverlay == null) {
            // Let the WebView handle direction keys for scrolling / navigation
            if (webView.dispatchKeyEvent(event)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        // Intentionally empty — prevents the system Back action.
    }

    // ── Diagnostics overlay ───────────────────────────────────────────────────

    private void toggleDiagnostics() {
        if (diagOverlay != null) {
            hideDiagnostics();
        } else {
            showDiagnostics();
        }
    }

    private void showDiagnostics() {
        if (diagOverlay != null) return;

        // Outer semi-transparent scrim
        FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0xCC000000);
        scrim.setClickable(true);

        // Card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFF1A1A2E);
        card.setPadding(dp(32), dp(32), dp(32), dp(32));

        // Title
        TextView title = new TextView(this);
        title.setText("Digipal Player — Diagnostics");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(24));
        card.addView(title);

        // Info rows
        addDiagRow(card, "Device",          Build.MANUFACTURER + " " + Build.MODEL);
        addDiagRow(card, "Android",         Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        addDiagRow(card, "App version",     getAppVersionName());
        addDiagRow(card, "Player URL",      getCurrentPlayerUrl());
        addDiagRow(card, "Auto-relaunch",   isAutoRelaunchEnabled() ? "enabled" : "disabled");
        addDiagRow(card, "Watchdog",        "active (10 s polling)");

        // Close button
        Button closeBtn = new Button(this);
        closeBtn.setText("Close");
        closeBtn.setTextColor(Color.WHITE);
        closeBtn.setBackgroundColor(0xFF16213E);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        btnParams.topMargin = dp(24);
        btnParams.gravity = Gravity.CENTER_HORIZONTAL;
        closeBtn.setLayoutParams(btnParams);
        closeBtn.setOnClickListener(v -> hideDiagnostics());
        card.addView(closeBtn);

        // Wrap card in a scroll view in case the screen is small
        ScrollView scroll = new ScrollView(this);
        scroll.addView(card);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        );
        cardLp.setMargins(dp(48), dp(48), dp(48), dp(48));
        scrim.addView(scroll, cardLp);

        rootLayout.addView(scrim, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        diagOverlay = scrim;
        hideSystemUI();
    }

    private void hideDiagnostics() {
        if (diagOverlay != null) {
            rootLayout.removeView(diagOverlay);
            diagOverlay = null;
        }
        if (webView != null) webView.requestFocus();
        hideSystemUI();
    }

    private void addDiagRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowLp.bottomMargin = dp(8);
        row.setLayoutParams(rowLp);

        TextView labelView = new TextView(this);
        labelView.setText(label + ": ");
        labelView.setTextColor(0xFFAAAAAA);
        labelView.setTextSize(14);
        labelView.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
            dp(160),
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        labelView.setLayoutParams(labelLp);
        row.addView(labelView);

        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextColor(Color.WHITE);
        valueView.setTextSize(14);
        row.addView(valueView);

        parent.addView(row);
    }

    private String getAppVersionName() {
        try {
            return getPackageManager()
                .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String getCurrentPlayerUrl() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String url = prefs.getString(KEY_SERVER_URL, null);
        return (url != null && !url.isEmpty()) ? url + "/tv" : "setup screen";
    }

    private int dp(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    // -------------------------------------------------------------------------
    // WebView setup
    // -------------------------------------------------------------------------

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
        settings.setDatabaseEnabled(true);
        settings.setTextZoom(100);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(false);
        }

        webView.setBackgroundColor(Color.BLACK);
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);

        webView.addJavascriptInterface(new WebAppInterface(), "Android");
        webView.setWebViewClient(buildWebViewClient());
        webView.setWebChromeClient(buildWebChromeClient());
    }

    private WebViewClient buildWebViewClient() {
        return new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                webView.setVisibility(View.VISIBLE);
                webView.requestFocus();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    scheduleRetry();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        };
    }

    /**
     * WebChromeClient with proper onShowCustomView / onHideCustomView.
     *
     * Without these overrides Android silently routes <video> fullscreen
     * requests to the system native player, which shows a circular
     * play-button overlay and ignores the app's landscape layout.
     *
     * onShowCustomView: attach the provided View to a full-screen FrameLayout
     *   on top of the WebView so video renders inside the app.
     * onHideCustomView: remove the view and restore the WebView.
     */
    private WebChromeClient buildWebChromeClient() {
        return new WebChromeClient() {

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (currentCustomView != null) {
                    onHideCustomView();
                }
                currentCustomView    = view;
                customViewCallback   = callback;

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
                if (currentCustomView == null) return;

                customViewContainer.removeView(currentCustomView);
                customViewContainer.setVisibility(View.GONE);
                currentCustomView = null;

                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }

                webView.setVisibility(View.VISIBLE);
                webView.requestFocus();
                hideSystemUI();
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                return true;
            }
        };
    }

    // -------------------------------------------------------------------------
    // Player loading and retry
    // -------------------------------------------------------------------------

    private void loadPlayer() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String serverUrl = prefs.getString(KEY_SERVER_URL, null);
        if (serverUrl != null && !serverUrl.isEmpty()) {
            webView.loadUrl(serverUrl.replaceAll("/+$", "") + "/tv");
        } else {
            webView.loadUrl("file:///android_asset/player-setup/index.html#platform=android");
        }
    }

    private void scheduleRetry() {
        if (retryRunnable != null) {
            retryHandler.removeCallbacks(retryRunnable);
        }
        retryRunnable = () -> loadPlayer();
        retryHandler.postDelayed(retryRunnable, 5000);
    }

    // -------------------------------------------------------------------------
    // Watchdog & relaunch
    // -------------------------------------------------------------------------

    private void startWatchdogService() {
        Intent intent = new Intent(this, WatchdogService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private boolean isAutoRelaunchEnabled() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(KEY_AUTO_RELAUNCH, true);
    }

    /**
     * Schedule a deferred app relaunch via BootLaunchService.
     *
     * Why BootLaunchService instead of PendingIntent.getActivity():
     * Android 12+ (API 31) blocks AlarmManager from starting Activities
     * directly when the app is not in the foreground. BootLaunchService is a
     * foreground service — it calls startForeground() first, then launches
     * MainActivity from a foreground context, which is legal on all versions.
     */
    void scheduleAppRelaunch(long delayMs) {
        Intent serviceIntent = new Intent(this, BootLaunchService.class);

        int piFlags = PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getService(this, 10, serviceIntent, piFlags);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) return;

        long triggerAt = System.currentTimeMillis() + delayMs;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            }
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        }
    }

    // -------------------------------------------------------------------------
    // System UI
    // -------------------------------------------------------------------------

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        decorView.setSystemUiVisibility(flags);
    }

    // -------------------------------------------------------------------------
    // JavaScript bridge
    // -------------------------------------------------------------------------

    private class WebAppInterface {

        @JavascriptInterface
        public void onReady(String payloadJson) {
            try {
                org.json.JSONObject obj = new org.json.JSONObject(payloadJson);
                String rawUrl = obj.optString("serverUrl", "").replaceAll("/+$", "");
                if (rawUrl.isEmpty()) return;

                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                prefs.edit().putString(KEY_SERVER_URL, rawUrl).apply();

                final String playerUrl = rawUrl + "/tv";
                runOnUiThread(() -> webView.loadUrl(playerUrl));
            } catch (Exception e) {
                android.util.Log.w("DigipalPlayer", "onReady parse error: " + e.getMessage());
            }
        }

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
        public String getDeviceInfo() {
            try {
                org.json.JSONObject info = new org.json.JSONObject();
                info.put("platform", "android");
                info.put("model", Build.MANUFACTURER + " " + Build.MODEL);
                info.put("androidVersion", Build.VERSION.RELEASE);
                info.put("sdkVersion", String.valueOf(Build.VERSION.SDK_INT));
                info.put("appVersion", getAppVersionName());
                return info.toString();
            } catch (Exception e) {
                return "{\"platform\":\"android\"}";
            }
        }
    }
}
