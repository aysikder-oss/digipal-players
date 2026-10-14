package com.nexuscast.player;

import static org.junit.Assert.*;
import org.junit.Test;

public class DevServerErrorTest {
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
