package com.nexuscast.player;

  import android.content.Context;
  import android.os.Handler;
  import android.os.Looper;
  import android.util.Log;
  import java.util.HashMap;
  import java.util.Map;

  /**
   * RecoveryCoordinator — ordered escalation replacing ad-hoc recovery scattered across
   * AppRecoverManager, RecoverWorker, BackupRecoverWorker, and ReliabilitySupervisor.
   *
   * Escalation levels (in order):
   *   SLIDE_RETRY      retry the current slide once more
   *   SLIDE_SKIP       skip the slide and advance playlist
   *   RENDERER_REBUILD release and recreate the active renderer
   *   WEBVIEW_REBUILD  rebuild the WebView renderer specifically
   *   PLAYLIST_ROLLBACK revert to the last known-good playlist revision
   *   SOFT_RESTART     MainActivity restart via AppRecoverManager.scheduleRecovery
   *   HARD_RESTART     immediate hard reboot via AppRecoverManager.scheduleRecovery
   *
   * Per-slide failure counters gate which level fires. All recovery events are logged
   * via window.__digipalNativeMetrics({type:'recovery', ...}) and
   * window.__digipalRecoveryReason is set before any soft/hard restart.
   */
  public class RecoveryCoordinator {

      public enum Level {
          SLIDE_RETRY, SLIDE_SKIP, RENDERER_REBUILD, WEBVIEW_REBUILD,
          PLAYLIST_ROLLBACK, SOFT_RESTART, HARD_RESTART
      }

      public interface EscalationDelegate {
          void onSlideRetry(String slideId, String reason);
          void onSlideSkip(String slideId, String reason);
          void onRendererRebuild(String reason);
          void onWebViewRebuild(String reason);
          void onPlaylistRollback(String reason);
          void onSoftRestart(String reason);
          void onHardRestart(String reason);
      }

      public interface JsBridge { void eval(String js); }

      private static final String TAG = "RecoveryCoordinator";

      // Escalation thresholds (same-slide or global failures)
      private static final int SLIDE_SKIP_THRESHOLD         = 3;
      private static final int RENDERER_REBUILD_THRESHOLD   = 5;
      private static final int WEBVIEW_REBUILD_THRESHOLD    = 8;
      private static final int PLAYLIST_ROLLBACK_THRESHOLD  = 10;
      private static final int SOFT_RESTART_THRESHOLD       = 12;
      private static final int HARD_RESTART_THRESHOLD       = 15;
      private static final long WEBVIEW_CRASH_WINDOW_MS     = 5 * 60_000L;
      private static final int  HARD_RESTART_WEBVIEW_LIMIT  = 3;

      private final Context            ctx;
      private final EscalationDelegate delegate;
      private final JsBridge           jsBridge;
      private final Handler            handler = new Handler(Looper.getMainLooper());

      private final Map<String, Integer> slideFailures = new HashMap<>();
      private int  globalFailures       = 0;
      private int  webViewCrashCount    = 0;
      private long firstWebViewCrashMs  = 0;

      public RecoveryCoordinator(Context ctx, EscalationDelegate delegate, JsBridge jsBridge) {
          this.ctx = ctx; this.delegate = delegate; this.jsBridge = jsBridge;
      }

      /**
       * Primary entry point — call when a slide asset fails to render.
       * @param slideId      current slide identifier (may be null)
       * @param assetId      asset/URL identifier (may be null)
       * @param reason       human-readable failure reason
       * @param rendererType "image", "video", "web", "pdf", etc.
       * @param memoryTier   current tier from MemoryBudgetManager (may be null)
       */
      public void reportSlideFailure(String slideId, String assetId, String reason,
                                     String rendererType, MemoryBudgetManager.Tier memoryTier) {
          int slideFails = 1;
          if (slideId != null && !slideId.isEmpty()) {
              slideFails = slideFailures.merge(slideId, 1, Integer::sum);
          }
          globalFailures++;

          Level level = computeLevel(slideFails);
          dispatchRecovery(level, slideId, assetId, reason, rendererType, memoryTier);
      }

      /**
       * Call on WebView renderer process crash. Tracks crash rate in a 5-minute window;
       * escalates to HARD_RESTART after 3 crashes in that window.
       */
      public void reportWebViewCrash(String reason, MemoryBudgetManager.Tier memoryTier) {
          long now = System.currentTimeMillis();
          if (webViewCrashCount == 0 || now - firstWebViewCrashMs > WEBVIEW_CRASH_WINDOW_MS) {
              webViewCrashCount = 1;
              firstWebViewCrashMs = now;
          } else {
              webViewCrashCount++;
          }
          Level level = (webViewCrashCount >= HARD_RESTART_WEBVIEW_LIMIT)
              ? Level.HARD_RESTART : Level.WEBVIEW_REBUILD;
          dispatchRecovery(level, null, null, reason, "webview", memoryTier);
      }

      /**
       * Called by ReliabilitySupervisor when its watchdog fires.
       * Routes the event at SOFT_RESTART level and delegates recovery to AppRecoverManager.
       */
      public void reportWatchdogTrigger(String reason, MemoryBudgetManager.Tier memoryTier) {
          dispatchRecovery(Level.SOFT_RESTART, null, null, reason, "watchdog", memoryTier);
      }

      /**
       * Log a non-escalating recovery event (e.g. WorkManager worker launch).
       * Does NOT increment failure counters or trigger further escalation.
       */
      public void logRecoveryEvent(String levelName, String reason, MemoryBudgetManager.Tier memoryTier) {
          String tierName = memoryTier != null ? memoryTier.name() : "UNKNOWN";
          Log.i(TAG, "[event] level=" + levelName + " reason=" + reason + " memory=" + tierName);
          String details = "{\"type\":\"recovery\","
              + "\"level\":\"" + levelName + "\","
              + "\"reason\":\"" + escJson(reason) + "\","
              + "\"memoryTier\":\"" + tierName + "\"" + "}";
          fireMetrics(details);
      }

      /** Call on each successful slide advance to decay failure counters. */
      public void onSlideSuccess(String slideId) {
          if (slideId != null) slideFailures.remove(slideId);
          if (globalFailures > 0) globalFailures = Math.max(0, globalFailures - 1);
      }

      // -----------------------------------------------------------------------

      private Level computeLevel(int slideFails) {
          if (slideFails >= SLIDE_SKIP_THRESHOLD)        return Level.SLIDE_SKIP;
          if (globalFailures >= HARD_RESTART_THRESHOLD)  return Level.HARD_RESTART;
          if (globalFailures >= SOFT_RESTART_THRESHOLD)  return Level.SOFT_RESTART;
          if (globalFailures >= PLAYLIST_ROLLBACK_THRESHOLD) return Level.PLAYLIST_ROLLBACK;
          if (globalFailures >= WEBVIEW_REBUILD_THRESHOLD)   return Level.WEBVIEW_REBUILD;
          if (globalFailures >= RENDERER_REBUILD_THRESHOLD)  return Level.RENDERER_REBUILD;
          return Level.SLIDE_RETRY;
      }

      private void dispatchRecovery(Level level, String slideId, String assetId,
                                     String reason, String rendererType,
                                     MemoryBudgetManager.Tier memoryTier) {
          String tierName = memoryTier != null ? memoryTier.name() : "UNKNOWN";
          Log.w(TAG, "[" + level + "] slide=" + slideId + " reason=" + reason
              + " rendererType=" + rendererType + " memory=" + tierName
              + " slideFails=" + (slideId != null ? slideFailures.getOrDefault(slideId, 0) : 0)
              + " globalFails=" + globalFailures);

          // JS metrics event
          String details = "{\"type\":\"recovery\","
              + "\"level\":\"" + level.name() + "\","
              + (slideId   != null ? "\"slideId\":\"" + escJson(slideId) + "\"," : "")
              + (assetId   != null ? "\"assetId\":\"" + escJson(assetId) + "\"," : "")
              + "\"reason\":\"" + escJson(reason) + "\","
              + "\"rendererType\":\"" + (rendererType != null ? rendererType : "") + "\","
              + "\"memoryTier\":\"" + tierName + "\","
              + "\"slideFailures\":" + (slideId != null ? slideFailures.getOrDefault(slideId, 0) : 0) + ","
              + "\"globalFailures\":" + globalFailures + "}";
          fireMetrics(details);

          // Set __digipalRecoveryReason before any restart
          if (level.ordinal() >= Level.SOFT_RESTART.ordinal()) {
              setRecoveryReason(level.name().toLowerCase() + ": " + reason);
          }

          // Escalate via delegate
          if (delegate == null) {
              // No delegate — fall back to direct AppRecoverManager for hard levels
              if (level == Level.SOFT_RESTART || level == Level.HARD_RESTART) {
                  AppRecoverManager.scheduleRecovery(ctx);
              }
              return;
          }
          switch (level) {
              case SLIDE_RETRY:       delegate.onSlideRetry(slideId, reason);    break;
              case SLIDE_SKIP:        delegate.onSlideSkip(slideId, reason);     break;
              case RENDERER_REBUILD:  delegate.onRendererRebuild(reason);        break;
              case WEBVIEW_REBUILD:   delegate.onWebViewRebuild(reason);         break;
              case PLAYLIST_ROLLBACK: delegate.onPlaylistRollback(reason);       break;
              case SOFT_RESTART:      delegate.onSoftRestart(reason);            break;
              case HARD_RESTART:      delegate.onHardRestart(reason);            break;
          }
      }

      private void fireMetrics(String jsonObj) {
          if (jsBridge == null) return;
          String js = "if(window.__digipalNativeMetrics){"
              + "try{window.__digipalNativeMetrics(" + jsonObj + ");}catch(e){}}";
          jsBridge.eval(js);
      }

      private void setRecoveryReason(String reason) {
          if (jsBridge == null) return;
          String js = "try{window.__digipalRecoveryReason=\"" + escJson(reason) + "\";}catch(e){}";
          jsBridge.eval(js);
      }

      private static String escJson(String s) {
          if (s == null) return "";
          return s.replace("\\", "\\\\").replace("\"", "\\\"")
                  .replace("\n", "\\n").replace("\r", "");
      }
  }
  