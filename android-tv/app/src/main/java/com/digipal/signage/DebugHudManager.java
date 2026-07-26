package com.digipal.signage;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * On-screen debug HUD for the Digipal Android TV player (debug builds only).
 *
 * Shows real-time diagnostic data overlaid on the player UI:
 *   - Current slide: content ID, type, duration
 *   - ExoPlayer / Glide renderer state
 *   - WebView visibility + dormancy state
 *   - Java heap + available memory
 *   - WebSocket heartbeat timestamp
 *
 * Toggle with the remote Info key (KEYCODE_INFO). The HUD is positioned in
 * the top-right corner and does not intercept input focus.
 *
 * Usage in MainActivity:
 *   // onCreate (after rootLayout is set up):
 *   if (BuildConfig.DEBUG) {
 *       debugHudManager = new DebugHudManager(rootLayout, this);
 *       debugHudManager.setDataProvider(() -> buildDebugHudText());
 *   }
 *
 *   // onKeyDown:
 *   if (keyCode == KeyEvent.KEYCODE_INFO) {
 *       if (BuildConfig.DEBUG && debugHudManager != null) debugHudManager.toggle();
 *       return true;
 *   }
 *
 *   // onDestroy:
 *   if (debugHudManager != null) debugHudManager.stop();
 */
public final class DebugHudManager {

    /** Implement to supply fresh HUD text each second while the HUD is visible. */
    public interface DataProvider {
        String getHudText();
    }

    private final TextView     hudView;
    private final Handler      handler;
    private       boolean      visible      = false;
    private       Runnable     tickRunnable = null;
    private       DataProvider dataProvider = null;

    private static final long TICK_INTERVAL_MS = 1_000L;

    public DebugHudManager(FrameLayout root, Context context) {
        hudView = new TextView(context);
        hudView.setTextColor(Color.WHITE);
        hudView.setTextSize(9f);
        hudView.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        hudView.setBackgroundColor(0xCC000000); // ~80% opaque black
        hudView.setPadding(14, 10, 14, 10);
        hudView.setVisibility(View.GONE);
        hudView.setClickable(false);
        hudView.setFocusable(false);
        hudView.setTypeface(android.graphics.Typeface.MONOSPACE);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity    = Gravity.TOP | Gravity.END;
        lp.topMargin  = 28;
        lp.rightMargin = 28;
        root.addView(hudView, lp);

        handler = new Handler(Looper.getMainLooper());
    }

    /** Register the callback that builds the HUD text string each second. */
    public void setDataProvider(DataProvider provider) {
        this.dataProvider = provider;
    }

    /** Show or hide the HUD and start/stop the 1 s refresh ticker. */
    public void toggle() {
        if (visible) {
            hide();
        } else {
            show();
        }
    }

    public void show() {
        if (visible) return;
        visible = true;
        hudView.setVisibility(View.VISIBLE);
        startTicking();
    }

    public void hide() {
        if (!visible) return;
        visible = false;
        hudView.setVisibility(View.GONE);
        stopTicking();
    }

    /** Call from Activity.onDestroy() to clean up the handler callback. */
    public void stop() {
        visible = false;
        stopTicking();
    }

    // -------------------------------------------------------------------------

    private void startTicking() {
        stopTicking();
        tickRunnable = new Runnable() {
            @Override public void run() {
                if (!visible) return;
                tick();
                handler.postDelayed(this, TICK_INTERVAL_MS);
            }
        };
        handler.post(tickRunnable);
    }

    private void stopTicking() {
        if (tickRunnable != null) {
            handler.removeCallbacks(tickRunnable);
            tickRunnable = null;
        }
    }

    private void tick() {
        if (dataProvider == null) {
            hudView.setText("[DEBUG HUD — no data provider]");
            return;
        }
        try {
            hudView.setText(dataProvider.getHudText());
        } catch (Throwable e) {
            hudView.setText("[DEBUG HUD error: " + e.getMessage() + "]");
        }
    }
}
