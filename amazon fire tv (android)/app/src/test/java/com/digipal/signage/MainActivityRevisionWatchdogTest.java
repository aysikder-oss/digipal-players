package com.digipal.signage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the revision watchdog decision logic extracted into
 * {@link MainActivity.PlayerWebViewClient#revisionWatchdogShouldReload}.
 *
 * These tests guard the fix for the black-screen bug where the 10-second
 * watchdog in applyContentRevisionFromNativeHeartbeat() was incorrectly
 * clearing native playback state when nativeSchedulerOwnsDisplay was true.
 *
 * No Android framework or Robolectric needed — the helper is a pure static
 * function of three booleans.
 */
public class MainActivityRevisionWatchdogTest {

    // ── Helper alias for readability ──────────────────────────────────────────

    private static boolean shouldReload(boolean nativeOwns, boolean hasNative, boolean dormant) {
        return MainActivity.PlayerWebViewClient.revisionWatchdogShouldReload(
                nativeOwns, hasNative, dormant);
    }

    // ── Native-active cases: must never reload ────────────────────────────────

    /**
     * Primary regression: nativeSchedulerOwnsDisplay=true, WebView dormant.
     * The old code cleared native state and forced a reload — causing a black screen.
     * Expected: watchdog returns false (skip reload, keep native playing).
     */
    @Test
    public void nativeOwnsDisplay_dormantWebView_doesNotReload() {
        assertFalse("Native owns display → must NOT reload",
                shouldReload(/*nativeOwns=*/true, /*hasNative=*/false, /*dormant=*/true));
    }

    /**
     * hasActiveNativePlaylist=true (e.g. transitioning between slides).
     * WebView dormant — must not reload.
     */
    @Test
    public void hasNativePlaylist_dormantWebView_doesNotReload() {
        assertFalse("Active native playlist → must NOT reload",
                shouldReload(false, true, true));
    }

    /**
     * Both flags true (redundant but possible during scheduler state transitions).
     */
    @Test
    public void bothNativeFlags_dormantWebView_doesNotReload() {
        assertFalse("Both native flags → must NOT reload",
                shouldReload(true, true, true));
    }

    /**
     * Native owns display, WebView is NOT dormant (edge case — unlikely in practice
     * since native play normally puts WebView dormant, but should not reload either way).
     */
    @Test
    public void nativeOwnsDisplay_webViewAwake_doesNotReload() {
        assertFalse("Native owns display, WebView awake → must NOT reload",
                shouldReload(true, false, false));
    }

    // ── WebView-awake cases (no native): must not reload ─────────────────────

    /**
     * No native playback, WebView is AWAKE. JS picks up the revision on the
     * next 30-s tvStatus poll — forcing a reload would disconnect the WebSocket.
     */
    @Test
    public void noNative_webViewAwake_doesNotReload() {
        assertFalse("WebView awake → must NOT reload",
                shouldReload(false, false, /*dormant=*/false));
    }

    // ── Only legitimate reload case ──────────────────────────────────────────

    /**
     * No native playback, WebView IS dormant, JS did not apply the revision.
     * This is the only case where a forced reload is warranted.
     */
    @Test
    public void noNative_dormantWebView_shouldReload() {
        assertTrue("No native + dormant WebView → SHOULD reload",
                shouldReload(false, false, /*dormant=*/true));
    }
}
