package com.digipal.signage;

import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;

/**
 * Thread-safe call recorder used by bridge instrumented tests.
 * The static field {@link MainActivity#__testBridgeRecorder} is set to an instance
 * of this class before the Activity is launched in tests; it is always null in
 * production (release) builds so there is zero runtime overhead.
 *
 * Kept in main source (not androidTest) so that the release APK can reference the
 * type without a compile-time error, while remaining a no-op at runtime.
 */
public final class BridgeCallRecorder {

    public static final class Entry {
        /** Bridge method name, e.g. "setNativePlaylist". */
        public final String method;
        /** JSON payload (may be truncated to 512 chars for readability). */
        public final String json;
        /** SystemClock.elapsedRealtime() at the moment of the call. */
        public final long timestampMs;

        Entry(String method, String json, long timestampMs) {
            this.method      = method;
            this.json        = json != null && json.length() > 512 ? json.substring(0, 512) + "\u2026" : json;
            this.timestampMs = timestampMs;
        }

        @Override
        public String toString() {
            return "[" + timestampMs + "ms] " + method + " " + json;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Object lock = new Object();

    /** Record a bridge call. Called from the JS-interface thread. */
    public void record(String method, String json) {
        Entry e = new Entry(method, json, SystemClock.elapsedRealtime());
        synchronized (lock) {
            entries.add(e);
            lock.notifyAll();
        }
    }

    /**
     * Block the calling thread until a call with the given method name arrives,
     * or until {@code timeoutMs} elapses.
     *
     * @return the matching Entry, or null on timeout.
     */
    public Entry awaitCall(String method, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while (true) {
                for (Entry e : entries) {
                    if (method.equals(e.method)) return e;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return null;
                lock.wait(remaining);
            }
        }
    }

    /**
     * Block until at least {@code minCount} calls have been recorded, or until the timeout.
     */
    public List<Entry> awaitMinCount(int minCount, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while (entries.size() < minCount) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                lock.wait(remaining);
            }
            return new ArrayList<>(entries);
        }
    }

    /** All recorded entries, in arrival order. */
    public List<Entry> all() {
        synchronized (lock) { return new ArrayList<>(entries); }
    }

    /** Reset the recorder between test phases. */
    public void clear() {
        synchronized (lock) { entries.clear(); }
    }
}
