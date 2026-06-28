package com.digipal.signage;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * HealthMonitor — single adaptive Handler loop that replaces three separate fixed-interval
 * timers (heartbeat stale watchdog, ReliabilitySupervisor, TelemetryManager heartbeat).
 *
 * Modes and their intervals:
 *   STARTUP       — first 3 min or non-PLAYING state             : health=10s  telemetry=30s
 *   STABLE_NATIVE — PLAYING with native slide for 3+ min         : health=60s  telemetry=120s
 *   STABLE_WEB    — PLAYING with web/design slide for 3+ min     : health=30s  telemetry=60s
 *   ERROR_RECOVERY — scheduler in ERROR_RECOVERY state           : health=5s   telemetry=30s
 */
public class HealthMonitor {

    public enum Mode { STARTUP, STABLE_NATIVE, STABLE_WEB, ERROR_RECOVERY }

    private static final String TAG = "HealthMonitor";

    private static final long STARTUP_DURATION_MS  = 3 * 60 * 1000L;
    private static final long STABLE_REQUIRED_MS   = 3 * 60 * 1000L;

    private static final long INTERVAL_STARTUP        = 10_000L;
    private static final long INTERVAL_STABLE_NATIVE  = 60_000L;
    private static final long INTERVAL_STABLE_WEB     = 30_000L;
    private static final long INTERVAL_ERROR          =  5_000L;

    private static final long TELEMETRY_STARTUP        =  30_000L;
    private static final long TELEMETRY_STABLE_NATIVE  = 120_000L;
    private static final long TELEMETRY_STABLE_WEB     =  60_000L;
    private static final long TELEMETRY_ERROR          =  30_000L;

    private static final long HEARTBEAT_STALE_MS         =  90_000L;
    private static final long WATCHDOG_RELOAD_BACKOFF_MS = 120_000L;

    public interface NetworkCheck  { boolean isNetworkConnected(); }
    public interface ReloadAction  { void forcePlayerReload(); }

    private final TelemetryManager      telemetry;
    private final ReliabilitySupervisor reliability;
    private final NetworkCheck          networkCheck;
    private final ReloadAction          reloadAction;

    private final Handler  handler = new Handler(Looper.getMainLooper());
    private Runnable tickRunnable;
    private boolean running = false;

    private Mode currentMode = Mode.STARTUP;
    private final long startMs = System.currentTimeMillis();

    // Scheduler state — updated via onSchedulerStateChanged() from MainActivity delegate
    private volatile PlaylistScheduler.State schedulerState = PlaylistScheduler.State.IDLE;
    private long playingStartMs = 0;

    // Current renderer type: "native" (video/image) or "web" (design/kiosk/url)
    private volatile String rendererType = "native";

    // Heartbeat stale check state (migrated from MainActivity)
    private volatile long    lastHeartbeatMs    = 0;
    private volatile boolean heartbeatReceived  = false;
    private long lastWatchdogReloadMs = 0;

    public HealthMonitor(TelemetryManager telemetry, ReliabilitySupervisor reliability,
                         NetworkCheck networkCheck, ReloadAction reloadAction) {
        this.telemetry   = telemetry;
        this.reliability = reliability;
        this.networkCheck = networkCheck;
        this.reloadAction = reloadAction;
        // Initialise heartbeat timestamp to avoid false-positive stale on first tick
        this.lastHeartbeatMs = System.currentTimeMillis();
    }

    public void start() {
        running = true;
        scheduleNext(INTERVAL_STARTUP);
        Log.i(TAG, "[start] HealthMonitor running, initial mode=" + currentMode);
    }

    public void stop() {
        running = false;
        if (tickRunnable != null) { handler.removeCallbacks(tickRunnable); tickRunnable = null; }
        Log.i(TAG, "[stop] HealthMonitor stopped");
    }

    /** Called from the JS heartbeat() bridge whenever the web player pings. */
    public void onHeartbeatReceived() {
        lastHeartbeatMs   = System.currentTimeMillis();
        heartbeatReceived = true;
    }

    /** Called from schedulerOnStateChanged delegate in MainActivity. Thread-safe. */
    public void onSchedulerStateChanged(PlaylistScheduler.State state) {
        this.schedulerState = state;
    }

    /** Called when a native (video/image) slide becomes active. Thread-safe. */
    public void setRendererTypeNative() { this.rendererType = "native"; }

    /** Called when a web (design/kiosk/url) slide becomes active. Thread-safe. */
    public void setRendererTypeWeb() { this.rendererType = "web"; }

    private void scheduleNext(long intervalMs) {
        tickRunnable = () -> {
            onTick();
            if (running) scheduleNext(intervalForMode(currentMode));
        };
        handler.postDelayed(tickRunnable, intervalMs);
    }

    private void onTick() {
        // 1. Recompute mode and notify dependents on change
        Mode newMode = computeMode();
        if (newMode != currentMode) {
            currentMode = newMode;
            long telemetryInterval = telemetryIntervalForMode(newMode);
            if (telemetry != null) telemetry.setHeartbeatInterval(telemetryInterval);
            Log.i(TAG, "[mode] " + newMode + " heartbeatInterval=" + telemetryInterval + "ms");
        }

        // 2. Tick ReliabilitySupervisor (replaces its internal Handler loop)
        if (reliability != null) {
            try { reliability.tick(); } catch (Throwable ignored) {}
        }

        // 3. Heartbeat stale check — only fires when the active renderer is WebView.
        // During native video/image playback WebView is deliberately dormant, so JS
        // heartbeats stop naturally; triggering a reload there would interrupt playback.
        if (heartbeatReceived && "web".equals(rendererType)) {
            long now = System.currentTimeMillis();
            if (now - lastHeartbeatMs > HEARTBEAT_STALE_MS
                    && networkCheck != null && networkCheck.isNetworkConnected()
                    && now - lastWatchdogReloadMs > WATCHDOG_RELOAD_BACKOFF_MS) {
                lastWatchdogReloadMs = now;
                lastHeartbeatMs = now; // avoid immediate re-fire
                Log.w(TAG, "[heartbeat_stale] Player heartbeat stale with network up - reloading WebView");
                if (telemetry != null) telemetry.logEvent("recovery", "heartbeat_stale",
                        "{\"reason\":\"heartbeat_stale_webview\",\"renderer\":\"web\"}");
                if (reloadAction != null) {
                    try { reloadAction.forcePlayerReload(); } catch (Throwable ignored) {}
                }
            }
        }
    }

    private Mode computeMode() {
        long now = System.currentTimeMillis();

        if (schedulerState == PlaylistScheduler.State.RECOVERING_RENDERER) {
            playingStartMs = 0;
            return Mode.ERROR_RECOVERY;
        }
        if (schedulerState == PlaylistScheduler.State.PLAYING) {
            if (playingStartMs == 0) playingStartMs = now;
            boolean stableWindow = (now - startMs >= STARTUP_DURATION_MS)
                    && (now - playingStartMs >= STABLE_REQUIRED_MS);
            if (stableWindow) {
                return "web".equals(rendererType) ? Mode.STABLE_WEB : Mode.STABLE_NATIVE;
            }
        } else {
            playingStartMs = 0;
        }
        return Mode.STARTUP;
    }

    private long intervalForMode(Mode mode) {
        switch (mode) {
            case STABLE_NATIVE:  return INTERVAL_STABLE_NATIVE;
            case STABLE_WEB:     return INTERVAL_STABLE_WEB;
            case ERROR_RECOVERY: return INTERVAL_ERROR;
            default:             return INTERVAL_STARTUP;
        }
    }

    private long telemetryIntervalForMode(Mode mode) {
        switch (mode) {
            case STABLE_NATIVE:  return TELEMETRY_STABLE_NATIVE;
            case STABLE_WEB:     return TELEMETRY_STABLE_WEB;
            case ERROR_RECOVERY: return TELEMETRY_ERROR;
            default:             return TELEMETRY_STARTUP;
        }
    }
}
