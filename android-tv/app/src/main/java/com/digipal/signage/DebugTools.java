package com.digipal.signage;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Debug-only diagnostic utilities for the Digipal Android TV player.
 *
 * All public methods check {@code BuildConfig.DEBUG} internally and are safe
 * to call unconditionally — they are no-ops in release builds. This class is
 * compiled into release APKs but has zero runtime overhead in production
 * because the JIT eliminates dead branches on a constant false.
 *
 * Tools included:
 *   - Bridge call logger (logcat tag: DigipalBridge)
 *   - Crash file writer (output: getExternalFilesDir("digipal-debug"))
 *   - Memory stats formatter for the debug HUD
 *   - OkHttp logging interceptor factory (tag: OkHttp)
 */
public final class DebugTools {

    private static final String TAG_BRIDGE = "DigipalBridge";
    private static final String TAG_CRASH  = "DigipalCrash";

    private DebugTools() {}

    // -------------------------------------------------------------------------
    // Bridge call logger
    // -------------------------------------------------------------------------

    /**
     * Logs a @JavascriptInterface bridge call to logcat.
     * Args are truncated to 200 chars to avoid flooding logcat with large
     * playlist JSON payloads.
     *
     * Usage (at the top of each @JavascriptInterface method):
     *   if (BuildConfig.DEBUG) DebugTools.logBridgeCall("methodName", args);
     */
    public static void logBridgeCall(String method, String args) {
        if (!BuildConfig.DEBUG) return;
        String truncated = (args != null && args.length() > 200)
                ? args.substring(0, 200) + "\u2026"
                : (args != null ? args : "");
        Log.d(TAG_BRIDGE, method + "(" + truncated + ")");
    }

    // -------------------------------------------------------------------------
    // OkHttp logging interceptor
    // -------------------------------------------------------------------------

    /**
     * Returns a configured HttpLoggingInterceptor with BASIC level (method,
     * URL, status, response time). Use HEADERS or BODY for more detail at the
     * cost of logcat volume.
     *
     * Usage:
     *   OkHttpClient.Builder builder = new OkHttpClient.Builder();
     *   if (BuildConfig.DEBUG) {
     *       okhttp3.logging.HttpLoggingInterceptor li = DebugTools.createOkHttpLoggingInterceptor();
     *       if (li != null) builder.addInterceptor(li);
     *   }
     *
     * Filter in logcat: adb logcat -s OkHttp
     */
    public static okhttp3.logging.HttpLoggingInterceptor createOkHttpLoggingInterceptor() {
        if (!BuildConfig.DEBUG) return null;
        okhttp3.logging.HttpLoggingInterceptor interceptor =
                new okhttp3.logging.HttpLoggingInterceptor(msg -> Log.d("OkHttp", msg));
        interceptor.setLevel(okhttp3.logging.HttpLoggingInterceptor.Level.BASIC);
        return interceptor;
    }

    // -------------------------------------------------------------------------
    // Crash file writer
    // -------------------------------------------------------------------------

    /**
     * Writes a human-readable crash report to external storage.
     * Includes: stack trace, device info, app version, last 50 logcat lines.
     *
     * Retrieve with:
     *   adb pull /sdcard/Android/data/com.digipal.signage.debug/files/digipal-debug/
     *
     * Call from UncaughtExceptionHandler before delegating to the previous handler:
     *   if (BuildConfig.DEBUG) {
     *       try { DebugTools.writeCrashReport(ctx, thread, throwable); } catch (Throwable ignored) {}
     *   }
     */
    public static void writeCrashReport(Context ctx, Thread thread, Throwable throwable) {
        if (!BuildConfig.DEBUG) return;
        try {
            File dir = ctx.getExternalFilesDir("digipal-debug");
            if (dir == null) dir = new File(ctx.getCacheDir(), "digipal-debug");
            if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();

            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File out = new File(dir, "crash_" + ts + ".txt");

            try (FileWriter fw = new FileWriter(out)) {
                fw.write("=== Digipal Debug Crash Report ===\n");
                fw.write("Time   : " + ts + "\n");
                fw.write("App    : " + BuildConfig.APPLICATION_ID + " v" + BuildConfig.VERSION_NAME
                        + " (" + BuildConfig.VERSION_CODE + ")\n");
                fw.write("Device : " + Build.MANUFACTURER + " " + Build.MODEL
                        + " API " + Build.VERSION.SDK_INT + "\n");
                fw.write("Thread : " + thread.getName() + "\n\n");

                fw.write("=== Stack Trace ===\n");
                java.io.StringWriter sw = new java.io.StringWriter();
                throwable.printStackTrace(new java.io.PrintWriter(sw));
                fw.write(sw.toString());
                fw.write("\n");

                fw.write("=== Last 50 Logcat Lines ===\n");
                try {
                    Process proc = Runtime.getRuntime().exec("logcat -d -t 50 -v brief");
                    BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                    String line;
                    while ((line = br.readLine()) != null) {
                        fw.write(line + "\n");
                    }
                    proc.destroy();
                } catch (Exception ignored) {
                    fw.write("(logcat unavailable)\n");
                }
            }
            Log.e(TAG_CRASH, "Crash report written: " + out.getAbsolutePath());
        } catch (Throwable e) {
            Log.e(TAG_CRASH, "Failed to write crash report", e);
        }
    }

    // -------------------------------------------------------------------------
    // Memory stats for the debug HUD
    // -------------------------------------------------------------------------

    /**
     * Returns a one-line memory summary string for display in the debug HUD.
     * Format: "heap 45/256MB avail 128MB" (plus "⚠LOW" if lowMemory flag set).
     */
    public static String getMemoryStats(Context ctx) {
        if (!BuildConfig.DEBUG) return "";
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            if (am != null) am.getMemoryInfo(mi);
            Runtime rt = Runtime.getRuntime();
            long heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576L;
            long heapMaxMb  = rt.maxMemory() / 1_048_576L;
            long availMb    = mi.availMem / 1_048_576L;
            return String.format(Locale.US, "heap %d/%dMB  avail %dMB%s",
                    heapUsedMb, heapMaxMb, availMb, mi.lowMemory ? "  \u26a0LOW" : "");
        } catch (Throwable e) {
            return "mem:err";
        }
    }
}
