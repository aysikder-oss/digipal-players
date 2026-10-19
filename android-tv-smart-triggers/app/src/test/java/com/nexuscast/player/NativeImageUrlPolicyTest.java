package com.nexuscast.player;

import org.junit.Test;
import static org.junit.Assert.*;

public class NativeImageUrlPolicyTest {
    private static final String OBJECT =
            "/replit-objstore-11111111-2222-3333-4444-555555555555"
            + "/.private/uploads/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String UPLOAD = "https://storage.googleapis.com" + OBJECT;

    @Test public void opaqueUploadedImagesAreAdmittedBeforeAndAfterUrlRefresh() {
        assertFalse(NativeImageUrlPolicy.isUnsafe(UPLOAD));
        assertFalse(NativeImageUrlPolicy.isUnsafe(UPLOAD + "?X-Goog-Signature=first"));
        assertFalse(NativeImageUrlPolicy.isUnsafe(UPLOAD + "?X-Goog-Signature=second"));
    }

    @Test public void bothImagesInAAndRepeatedCyclesUseTheSameAdmissionRule() {
        String second = UPLOAD.replace("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                "00000000-1111-2222-3333-444444444444");
        for (int cycle = 0; cycle < 100; cycle++) {
            assertFalse(NativeImageUrlPolicy.isUnsafe(UPLOAD));
            assertFalse(NativeImageUrlPolicy.isUnsafe(second));
        }
    }

    @Test public void canonicalAndOfflineNativeSourcesRemainSupported() {
        assertFalse(NativeImageUrlPolicy.isUnsafe("/objects/uploads/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
        assertFalse(NativeImageUrlPolicy.isUnsafe("file:///data/user/0/player/files/media/opaque-cache-key"));
        assertFalse(NativeImageUrlPolicy.isUnsafe("content://player/media/photo"));
    }

    @Test public void externalRasterFilesRemainSupported() {
        for (String ext : new String[]{"jpg", "JPEG", "png", "webp", "gif", "bmp", "avif"}) {
            assertFalse(ext, NativeImageUrlPolicy.isUnsafe("https://cdn.example.test/photo." + ext + "?v=1"));
        }
    }

    @Test public void videoAndSvgRestrictionsRemainInForce() {
        for (String ext : new String[]{"mp4", "MOV", "webm", "avi", "m4v", "mkv", "ts", "SVG"}) {
            assertTrue(ext, NativeImageUrlPolicy.isUnsafe(UPLOAD + "." + ext + "?v=1"));
            assertTrue(ext, NativeImageUrlPolicy.isUnsafe("https://cdn.example.test/photo." + ext));
            assertTrue(ext, NativeImageUrlPolicy.isUnsafe("file:///data/cache/photo." + ext));
        }
    }

    @Test public void arbitraryExtensionlessExternalUrlsStayRejected() {
        assertTrue(NativeImageUrlPolicy.isUnsafe("https://placehold.co/1920x1080?text=hello"));
        assertTrue(NativeImageUrlPolicy.isUnsafe("https://cdn.example.test/opaque"));
        assertTrue(NativeImageUrlPolicy.isUnsafe("https://storage.googleapis.com/other-bucket/opaque"));
        assertTrue(NativeImageUrlPolicy.isUnsafe("https://storage.googleapis.com" + OBJECT + "/extra"));
    }

    @Test public void storageLookalikesAndUntrustedOriginsAreNotExempted() {
        assertTrue(NativeImageUrlPolicy.isUnsafe("http://storage.googleapis.com" + OBJECT));
        assertTrue(NativeImageUrlPolicy.isUnsafe("https://storage.googleapis.com.evil.test" + OBJECT));
        assertTrue(NativeImageUrlPolicy.isUnsafe("https://evil.test" + OBJECT));
        assertTrue(NativeImageUrlPolicy.isUnsafe("https://user@storage.googleapis.com" + OBJECT));
        assertTrue(NativeImageUrlPolicy.isUnsafe("https://storage.googleapis.com:444" + OBJECT));
        assertTrue(NativeImageUrlPolicy.isUnsafe(UPLOAD + "#fragment"));
    }

    @Test public void encodedPathsAndMalformedUploadIdsAreNotExempted() {
        assertTrue(NativeImageUrlPolicy.isUnsafe(UPLOAD.replace("/.private/", "/%2eprivate/")));
        assertTrue(NativeImageUrlPolicy.isUnsafe(UPLOAD.replace("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "not-an-upload")));
        assertTrue(NativeImageUrlPolicy.isUnsafe(UPLOAD.replace("11111111-2222-3333-4444-555555555555", "not-a-bucket")));
    }

    @Test public void browserOnlyMediaUrlsAreNotMistakenForNativeStorage() {
        assertTrue(NativeImageUrlPolicy.isUnsafe("https://appassets.androidplatform.net/media/opaque"));
    }

    @Test public void emptyMediaIsRejected() {
        assertTrue(NativeImageUrlPolicy.isUnsafe(null));
        assertTrue(NativeImageUrlPolicy.isUnsafe(""));
        assertTrue(NativeImageUrlPolicy.isUnsafe("  "));
    }
}
