package com.digipal.offline;

import org.junit.Test;
import static org.junit.Assert.*;

public class MediaSourceIdentityTest {
    @Test public void unknownSourceDoesNotProveReadiness() {
        assertFalse(MediaSourceIdentity.matches("", "https://example.com/image.jpg"));
        assertFalse(MediaSourceIdentity.matches(null, null));
    }
    @Test public void renewedKnownSignaturesKeepIdentity() {
        assertTrue(MediaSourceIdentity.matches(
            "https://example.com/video.mp4?X-Goog-Signature=fixture-one&X-Goog-Expires=30",
            "https://example.com/video.mp4?X-Goog-Signature=fixture-two&X-Goog-Expires=60"));
    }
    @Test public void semanticVersionsAreNotAuthentication() {
        assertFalse(MediaSourceIdentity.matches(
            "https://example.com/image.jpg?version=one&Signature=fixture",
            "https://example.com/image.jpg?version=two&Signature=fixture"));
        assertFalse(MediaSourceIdentity.matches(
            "https://example.com/image.jpg?generation=1",
            "https://example.com/image.jpg?generation=2"));
    }
    @Test public void unknownTokensAndQueryOrderingRemainMeaningful() {
        assertFalse(MediaSourceIdentity.matches("https://example.com/a?token=one",
            "https://example.com/a?token=two"));
        assertFalse(MediaSourceIdentity.matches("https://example.com/a?a=1&b=2",
            "https://example.com/a?b=2&a=1"));
    }
    @Test public void schemeHostAndDefaultPortNormalize() {
        assertTrue(MediaSourceIdentity.matches("HTTPS://EXAMPLE.COM:443/a", "https://example.com/a"));
        assertFalse(MediaSourceIdentity.matches("http://example.com/a", "https://example.com/a"));
    }
    @Test public void invalidNativeAndCredentialUrlsNeverMatch() {
        for (String source : new String[]{"file:///private/a", "blob:fixture",
                "https://user:fixture@example.com/a", "https://[invalid/a"}) {
            assertFalse(MediaSourceIdentity.matches(source, source));
        }
    }
}
