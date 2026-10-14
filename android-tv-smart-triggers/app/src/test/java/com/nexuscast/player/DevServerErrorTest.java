package com.nexuscast.player;

import static org.junit.Assert.*;
import org.junit.Test;

public class DevServerErrorTest {
    private static final String TARGET =
            "https://3922e89f-d02d-4a8b-add9-9f42fce9e5ea-00-sisthqoc0pvv.worf.replit.dev";
    private static final String OBSOLETE =
            "https://3922e89f-d02d-4a8b-add9-9f42fce9e5ea-00-sisthqoc0pvv-xrwo385d.worf.replit.dev:5000";

    @Test public void onlyKnownUnpairedCloudDevOverridesAreReset() {
        assertTrue(MainActivity.shouldResetDevOrigin("offlineTest", true, "cloud", OBSOLETE, TARGET));
        assertTrue(MainActivity.shouldResetDevOrigin("offlineTest", true, "cloud", TARGET + ":5000/", TARGET));
        assertFalse(MainActivity.shouldResetDevOrigin("offlineTest", true, "cloud", TARGET, TARGET));
        assertFalse(MainActivity.shouldResetDevOrigin("offlineTest", false, "cloud", OBSOLETE, TARGET));
        assertFalse(MainActivity.shouldResetDevOrigin("offlineTest", true, "local", OBSOLETE, TARGET));
        assertFalse(MainActivity.shouldResetDevOrigin("release", true, "cloud", OBSOLETE, TARGET));
        assertFalse(MainActivity.shouldResetDevOrigin("offlineProductionTest", true, "cloud", OBSOLETE, TARGET));
        assertFalse(MainActivity.shouldResetDevOrigin("offlineTest", true, "cloud", "https://other.example", TARGET));
        assertFalse(MainActivity.shouldResetDevOrigin("offlineTest", true, "cloud", null, TARGET));
        assertFalse(MainActivity.shouldResetDevOrigin("offlineTest", true, "cloud", "not a URI", TARGET));
    }

    @Test public void devRejectsUnavailableAndUnauthorizedPages() {
        for (int status : new int[] {400, 401, 403, 404, 429, 500, 502, 503}) {
            assertTrue(MainActivity.isDevServerHttpError("offlineTest", status));
        }
    }

    @Test public void successAndRedirectsRemainUnchanged() {
        for (int status : new int[] {200, 204, 301, 302, 304}) {
            assertFalse(MainActivity.isDevServerHttpError("offlineTest", status));
        }
    }

    @Test public void otherVariantsAreNotChanged() {
        for (String variant : new String[] {"release", "debug", "offlineProductionTest"}) {
            assertFalse(MainActivity.isDevServerHttpError(variant, 404));
            assertFalse(MainActivity.isDevServerHttpError(variant, 503));
        }
    }
}
