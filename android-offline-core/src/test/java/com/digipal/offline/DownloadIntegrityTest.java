package com.digipal.offline;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class DownloadIntegrityTest {
    @Test public void parsesLongLengthsWithoutRequiringApi24() {
        assertEquals(5_000_000_000L, DownloadIntegrity.declaredLength("5000000000"));
        assertEquals(-1, DownloadIntegrity.declaredLength(null));
        assertEquals(-2, DownloadIntegrity.declaredLength("broken"));
        assertEquals(-2, DownloadIntegrity.declaredLength("-1"));
    }
    @Test public void requiresExactLengthBeforePromotion() throws Exception {
        File file = File.createTempFile("offline-transfer", ".tmp");
        try {
            Files.write(file.toPath(), new byte[96]);
            assertFalse(DownloadIntegrity.isComplete(file, 96, 100, "video/mp4"));
            assertFalse(DownloadIntegrity.isComplete(file, 100, 100, "video/mp4"));
            assertTrue(DownloadIntegrity.isComplete(file, 96, 96, "video/mp4"));
            assertTrue(DownloadIntegrity.isComplete(file, 96, -1, "video/mp4"));
        } finally { file.delete(); }
    }

    @Test public void rejectsEmptyAndHtmlErrorResponses() throws Exception {
        File file = File.createTempFile("offline-transfer", ".tmp");
        try {
            assertFalse(DownloadIntegrity.isComplete(file, 0, -1, null));
            Files.write(file.toPath(), new byte[5]);
            assertFalse(DownloadIntegrity.isComplete(file, 5, 5, "TEXT/HTML; charset=UTF-8"));
            assertFalse(DownloadIntegrity.isComplete(file, 5, 5, "application/xhtml+xml"));
            assertTrue(DownloadIntegrity.isComplete(file, 5, 5, "image/png"));
            file.delete();
            assertFalse(DownloadIntegrity.isComplete(file, 5, 5, "image/png"));
        } finally { file.delete(); }
    }

    @Test public void restoredMetadataDoesNotProveBytesAreAvailable() throws Exception {
        File file = File.createTempFile("offline-availability", ".tmp");
        try {
            assertFalse(DownloadIntegrity.isAvailable(file, -1));
            Files.write(file.toPath(), new byte[5]);
            assertTrue(DownloadIntegrity.isAvailable(file, -1));
            assertTrue(DownloadIntegrity.isAvailable(file, 5));
            assertFalse(DownloadIntegrity.isAvailable(file, 8));
            assertFalse(DownloadIntegrity.isAvailable(file, 0));
            assertFalse(DownloadIntegrity.isAvailable(file, -2));
            file.delete();
            assertFalse(DownloadIntegrity.isAvailable(file, 5));
        } finally { file.delete(); }
    }
}
