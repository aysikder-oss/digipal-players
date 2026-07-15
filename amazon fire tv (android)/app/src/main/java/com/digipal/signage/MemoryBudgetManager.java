package com.digipal.signage;

  import android.app.ActivityManager;
  import android.content.Context;
  import android.os.Handler;
  import android.os.Looper;
  import android.util.Log;

  /**
   * MemoryBudgetManager — single authority for memory tier policy.
   * Polls Java heap + ActivityManager every 5 s and enforces tiered behaviour:
   *   NORMAL   heap < 60%, avail > 100 MB — full preload enabled
   *   LOW      heap 60–80% or avail 50–100 MB — background preload disabled
   *   CRITICAL heap > 80% or avail < 50 MB  — one renderer only, no idle WebView,
   *                                            maintenance restart if revision verified
   *
   * Fires window.__digipalNativeMetrics({type:'memoryPressure', tier, heapPercent, availMb})
   * via the supplied JsBridge whenever the tier changes.
   */
  public class MemoryBudgetManager {

      public enum Tier { NORMAL, LOW, CRITICAL }

      public interface BudgetListener {
          /** Invoked on the main thread when the memory tier changes. */
          void onTierChanged(Tier oldTier, Tier newTier);
      }

      public interface JsBridge {
          /** Evaluate JavaScript in the current WebView context. */
          void eval(String js);
      }

      private static final String TAG = "MemoryBudgetManager";
      private static final long POLL_INTERVAL_MS     = 5_000L;
      private static final int  HEAP_LOW_PCT         = 60;
      private static final int  HEAP_CRITICAL_PCT    = 80;
      private static final long AVAIL_LOW_MB         = 100L;
      private static final long AVAIL_CRITICAL_MB    = 50L;

      private final Context       ctx;
      private final BudgetListener listener;
      private final JsBridge      jsBridge;
      private final Handler       handler = new Handler(Looper.getMainLooper());
      private Runnable pollRunnable;

      private volatile Tier currentTier = Tier.NORMAL;
      private volatile boolean running  = false;

      public MemoryBudgetManager(Context ctx, BudgetListener listener, JsBridge jsBridge) {
          this.ctx = ctx; this.listener = listener; this.jsBridge = jsBridge;
      }

      public void start() {
          running = true;
          schedulePoll();
          Log.i(TAG, "started");
      }

      public void stop() {
          running = false;
          if (pollRunnable != null) { handler.removeCallbacks(pollRunnable); pollRunnable = null; }
      }

      public Tier getCurrentTier() { return currentTier; }

      private void schedulePoll() {
          if (!running) return;
          pollRunnable = () -> { poll(); schedulePoll(); };
          handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
      }

      private void poll() {
          ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
          if (am == null) return;
          ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
          am.getMemoryInfo(mi);

          long usedHeap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
          long maxHeap  = Runtime.getRuntime().maxMemory();
          int  heapPct  = (int) (maxHeap > 0 ? usedHeap * 100L / maxHeap : 0);
          long availMb  = mi.availMem / (1024L * 1024L);

          Tier newTier;
          if (heapPct > HEAP_CRITICAL_PCT || availMb < AVAIL_CRITICAL_MB) {
              newTier = Tier.CRITICAL;
          } else if (heapPct > HEAP_LOW_PCT || availMb < AVAIL_LOW_MB) {
              newTier = Tier.LOW;
          } else {
              newTier = Tier.NORMAL;
          }

          if (newTier != currentTier) {
              Tier old = currentTier;
              currentTier = newTier;
              Log.i(TAG, "tier " + old + " -> " + newTier
                  + " (heap=" + heapPct + "%, avail=" + availMb + "MB)");
              fireMetricsEvent(newTier, heapPct, availMb);
              if (listener != null) listener.onTierChanged(old, newTier);
          }
      }

      private void fireMetricsEvent(Tier tier, int heapPct, long availMb) {
          if (jsBridge == null) return;
          String js = "if(window.__digipalNativeMetrics){"
              + "try{window.__digipalNativeMetrics({"
              + "type:'memoryPressure',"
              + "tier:'" + tier.name() + "',"
              + "heapPercent:" + heapPct + ","
              + "availMb:" + availMb
              + "});}catch(e){}}";
          jsBridge.eval(js);
      }
  }
  