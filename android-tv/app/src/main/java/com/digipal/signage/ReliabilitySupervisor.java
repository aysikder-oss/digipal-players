package com.digipal.signage;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ReliabilitySupervisor — coordinates soft / medium / hard recovery.
 * Integrates with existing WatchdogService and AppRecoverManager.
 *
 * Soft recovery (per-slide):  retry slide, use fallback, extend duration
 * Medium recovery:            restart scheduler, recreate renderer pools
 * Hard recovery:              restart MainActivity via AppRecoverManager
 *
 * Watches: scheduler advancing, memory pressure, storage pressure, heartbeat.
 */
public class ReliabilitySupervisor {

    private static final String TAG = "ReliabilitySupervisor";
    private static final long CHECK_INTERVAL_MS  = 30_000;
    private static final long MAX_IDLE_MS        = 120_000; // 2× max slide duration
    private static final long MIN_FREE_STORAGE   = 100 * 1024 * 1024L; // 100 MB
    private static final int  SOFT_BEFORE_MEDIUM = 5;
    private static final int  MEDIUM_BEFORE_HARD = 3;

    public interface RecoveryDelegate {
        void softRecover(String reason);
        void mediumRecover(String reason);
        void hardRecover(String reason);
    }

    private final Context ctx;
    private final RecoveryDelegate delegate;
    private final TelemetryManager telemetry;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final AtomicLong lastSchedulerAdvanceMs = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastHeartbeatMs        = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger softCount           = new AtomicInteger(0);
    private final AtomicInteger mediumCount         = new AtomicInteger(0);

    private Runnable checkRunnable;
      private boolean running = false;

      // Optional RecoveryCoordinator — set by MainActivity after construction.
      // When set, hard() routes through it rather than calling AppRecoverManager directly.
      private RecoveryCoordinator recoveryCoordinator;
      // Optional MemoryBudgetManager — used to pass memory tier to coordinator.
      private MemoryBudgetManager memoryBudgetManager;

    public ReliabilitySupervisor(Context ctx, RecoveryDelegate delegate, TelemetryManager telemetry) {
        this.ctx = ctx; this.delegate = delegate; this.telemetry = telemetry;
    }

    public void setRecoveryCoordinator(RecoveryCoordinator coordinator) {
          this.recoveryCoordinator = coordinator;
      }

      public void setMemoryBudgetManager(MemoryBudgetManager mbm) {
          this.memoryBudgetManager = mbm;
      }

      public void start() {
        running = true;
        scheduleCheck();
        Log.i(TAG, "started");
    }

    public void stop() {
        running = false;
        if (checkRunnable != null) { handler.removeCallbacks(checkRunnable); checkRunnable = null; }
    }

    /** Call on every slide transition to reset the stall detector. */
    public void reportSchedulerAdvance() {
        lastSchedulerAdvanceMs.set(System.currentTimeMillis());
        softCount.set(0);
    }

    /** Call on WebView heartbeat bridge callback. */
    public void reportHeartbeat() { lastHeartbeatMs.set(System.currentTimeMillis()); }

    /** Call when any renderer encounters an error. */
    public void reportError(String component, String error) {
        Log.w(TAG, "[error] " + component + ": " + error);
        if (telemetry != null) {
            PlaylistDatabase.PlayerErrorEntity e = new PlaylistDatabase.PlayerErrorEntity();
            e.timestamp = System.currentTimeMillis(); e.component = component; e.message = error;
        }
        int sc = softCount.incrementAndGet();
        if (sc >= SOFT_BEFORE_MEDIUM) {
            int mc = mediumCount.incrementAndGet();
            softCount.set(0);
            if (mc >= MEDIUM_BEFORE_HARD) {
                mediumCount.set(0);
                hard("repeated_medium_failures");
            } else {
                medium("soft_count_exceeded: " + component);
            }
        } else {
            soft("renderer_error: " + component);
        }
    }

    /**
     * External tick — called by HealthMonitor on each of its adaptive cycles instead of
     * running ReliabilitySupervisor's own internal Handler loop.
     */
    public void tick() {
        if (running) check();
    }

    /**
     * Start in externally-clocked mode: marks running=true but does NOT schedule the
     * internal Handler loop. HealthMonitor will drive checks via tick().
     */
    public void startExternallyClocked() {
        running = true;
        Log.i(TAG, "started (externally clocked by HealthMonitor)");
    }

    private void scheduleCheck() {
        if (!running) return;
        checkRunnable = () -> {
            check();
            scheduleCheck();
        };
        handler.postDelayed(checkRunnable, CHECK_INTERVAL_MS);
    }

    private void check() {
        long now = System.currentTimeMillis();

        // Scheduler stall: no advance for MAX_IDLE_MS
        if (now - lastSchedulerAdvanceMs.get() > MAX_IDLE_MS) {
            Log.w(TAG, "[stall] scheduler hasn't advanced in " + (now - lastSchedulerAdvanceMs.get()) + "ms");
            reportError("scheduler", "stall");
        }

        // Memory pressure
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        if (mi.lowMemory) {
            Log.w(TAG, "[memory] low memory pressure detected");
            soft("low_memory");
        }

        // Storage pressure
        try {
            long free = new StatFs(ctx.getFilesDir().getPath()).getAvailableBytes();
            if (free < MIN_FREE_STORAGE) {
                Log.w(TAG, "[storage] only " + (free / 1024 / 1024) + " MB free");
                soft("low_storage");
            }
        } catch (Exception e) { /* ignore */ }
    }

    private void soft(String reason) {
        Log.i(TAG, "[soft] " + reason);
        if (delegate != null) delegate.softRecover(reason);
    }

    private void medium(String reason) {
        Log.w(TAG, "[medium] " + reason);
        if (delegate != null) delegate.mediumRecover(reason);
    }

    private void hard(String reason) {
        Log.e(TAG, "[hard] " + reason + " — scheduling AppRecoverManager restart");
        AppRecoverManager.scheduleRecovery(ctx);
        if (delegate != null) delegate.hardRecover(reason);
    }
}
