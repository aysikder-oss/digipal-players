package com.nexuscast.player;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.StatFs;
import android.util.Log;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TelemetryManager — logs playback events to Room and POSTs heartbeat + event batches to the server.
 * Tracks per-slide performance counters: transition gap, first frame, rebuffers, etc.
 */
public class TelemetryManager {

    private static final String TAG = "TelemetryManager";
    private static final long HEARTBEAT_INTERVAL_MS = 30_000;
    private volatile long currentHeartbeatIntervalMs = HEARTBEAT_INTERVAL_MS;
    private static final int MAX_BATCH = 50;

    private final Context ctx;
    private final PlaylistRepository repo;
    private final String serverUrl;
    private final String deviceId;
    private final String appVersion;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    // Runtime state reported in heartbeat
    private volatile String currentRevisionId = "";
    private volatile String currentSlideId = "";
    private volatile String currentRendererType = "";
    private volatile String lastError = "";
    private volatile int cacheReadyPercent = 100;
    private final AtomicLong transitionGapMs = new AtomicLong(0);
    private long lastSlideHideMs = 0;

    private java.util.Timer heartbeatTimer;

    public TelemetryManager(Context ctx, PlaylistRepository repo, String serverUrl) {
        this.ctx = ctx;
        this.repo = repo;
        this.serverUrl = serverUrl;
        this.deviceId = Build.SERIAL.equals(Build.UNKNOWN)
                ? android.provider.Settings.Secure.getString(ctx.getContentResolver(),
                    android.provider.Settings.Secure.ANDROID_ID)
                : Build.SERIAL;
        this.appVersion = BuildConfig.VERSION_NAME;
    }

    public void start() {
        scheduleHeartbeatTimer(currentHeartbeatIntervalMs);
        Log.i(TAG, "started");
    }

    private synchronized void scheduleHeartbeatTimer(long intervalMs) {
        if (heartbeatTimer != null) { heartbeatTimer.cancel(); heartbeatTimer = null; }
        heartbeatTimer = new java.util.Timer("DigipalHeartbeat", true);
        heartbeatTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override public void run() { sendHeartbeat(); syncEvents(); }
        }, intervalMs, intervalMs);
    }

    /**
     * Adjust heartbeat frequency. Called by HealthMonitor when playback mode changes.
     * Changes take effect on the next timer cycle.
     */
    public void setHeartbeatInterval(long intervalMs) {
        if (intervalMs == currentHeartbeatIntervalMs) return;
        currentHeartbeatIntervalMs = intervalMs;
        scheduleHeartbeatTimer(intervalMs);
        Log.i(TAG, "[heartbeat_interval] set to " + intervalMs + "ms");
    }

    public void stop() {
        if (heartbeatTimer != null) { heartbeatTimer.cancel(); heartbeatTimer = null; }
        exec.shutdown();
    }

    /** Log a playback event — persisted to Room, batched to server on next heartbeat. */
    public void logEvent(String eventType, String slideId, String detailsJson) {
        final long ts = System.currentTimeMillis();
        exec.execute(() -> {
            PlaylistDatabase.PlaybackEventEntity e = new PlaylistDatabase.PlaybackEventEntity();
            e.timestamp    = ts;
            e.revisionId   = currentRevisionId;
            e.slideId      = slideId;
            e.eventType    = eventType;
            e.rendererType = currentRendererType;
            e.detailsJson  = detailsJson;
            repo.getDb().eventDao().insert(e);
        });
    }

    public void setCurrentSlide(String revId, String slideId, String rendererType) {
        currentRevisionId = revId;
        currentSlideId    = slideId;
        currentRendererType = rendererType;
        // Track transition gap
        if (lastSlideHideMs > 0) {
            transitionGapMs.set(System.currentTimeMillis() - lastSlideHideMs);
        }
    }

    public void onSlideHidden() { lastSlideHideMs = System.currentTimeMillis(); }
    public void setLastError(String err) { lastError = err; }
    public void setCacheReadyPercent(int pct) { cacheReadyPercent = pct; }

    private void sendHeartbeat() {
        try {
            JSONObject payload = buildHeartbeat();
            postJson(serverUrl + "/api/tv/telemetry/heartbeat", payload.toString());
        } catch (Exception e) {
            Log.w(TAG, "heartbeat failed: " + e.getMessage());
        }
    }

    private void syncEvents() {
        exec.execute(() -> {
            try {
                List<PlaylistDatabase.PlaybackEventEntity> events = repo.getDb().eventDao().getUnsynced();
                if (events.isEmpty()) return;
                JSONArray arr = new JSONArray();
                List<Long> ids = new ArrayList<>();
                for (PlaylistDatabase.PlaybackEventEntity ev : events) {
                    JSONObject o = new JSONObject();
                    o.put("timestamp", ev.timestamp); o.put("eventType", ev.eventType);
                    o.put("slideId", ev.slideId); o.put("revisionId", ev.revisionId);
                    o.put("rendererType", ev.rendererType);
                    try { o.put("details", new JSONObject(ev.detailsJson)); } catch (Exception ex) {}
                    arr.put(o); ids.add(ev.id);
                    if (ids.size() >= MAX_BATCH) break;
                }
                JSONObject body = new JSONObject();
                body.put("deviceId", deviceId);
                body.put("events", arr);
                postJson(serverUrl + "/api/tv/telemetry/events", body.toString());
                repo.getDb().eventDao().markSynced(ids);
                repo.getDb().eventDao().pruneOld(System.currentTimeMillis() - 3L * 86400 * 1000);
            } catch (Exception e) {
                Log.w(TAG, "syncEvents failed: " + e.getMessage());
            }
        });
    }

    private JSONObject buildHeartbeat() throws JSONException {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long freeStorage = 0;
        try { freeStorage = new StatFs(ctx.getFilesDir().getPath()).getAvailableBytes(); } catch (Exception e) {}
        String memPressure = mi.lowMemory ? "HIGH" : (mi.availMem < 200 * 1024 * 1024L) ? "MODERATE" : "NORMAL";

        JSONObject o = new JSONObject();
        o.put("deviceId", deviceId);
        o.put("appVersion", appVersion);
        o.put("uptimeMs", android.os.SystemClock.elapsedRealtime());
        o.put("playlistRevision", currentRevisionId);
        o.put("currentSlideId", currentSlideId);
        o.put("rendererType", currentRendererType);
        o.put("cacheReadyPercent", cacheReadyPercent);
        o.put("freeStorageBytes", freeStorage);
        o.put("memoryPressure", memPressure);
        o.put("lastError", lastError);
        o.put("transitionGapMs", transitionGapMs.get());
        o.put("heartbeatIntervalMs", currentHeartbeatIntervalMs);
        return o;
    }

    private void postJson(String urlStr, String body) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes("UTF-8"));
        }
        int code = conn.getResponseCode();
        if (code >= 400) Log.w(TAG, "POST " + urlStr + " returned " + code);
        conn.disconnect();
    }
}
