package com.nexuscast.player;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import java.io.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DevLocalMediaResponseTest {
    private File file;
    private static final int LENGTH = 38_000_000;
    private static final long LATER = 37_000_000;
    @Before public void prepare() throws Exception {
        file = File.createTempFile("dev-local-range-test", ".bin");
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            out.setLength(LENGTH);
            out.seek(LATER);
            out.write(new byte[]{11, 22, 33, 44});
            out.seek(LENGTH - 1);
            out.write(99);
        }
    }
    @After public void cleanup() { file.delete(); }
    private WebResourceRequest request(final String range, final String method) {
        return new WebResourceRequest() {
            public Uri getUrl() { return Uri.parse("https://appassets.androidplatform.net/media/test"); }
            public boolean isForMainFrame() { return false; }
            public boolean isRedirect() { return false; }
            public boolean hasGesture() { return false; }
            public String getMethod() { return method; }
            public Map<String,String> getRequestHeaders() {
                return range == null ? Collections.emptyMap() : Collections.singletonMap("rAnGe", range);
            }
        };
    }
    private WebResourceResponse response(String range, String method) throws IOException {
        WebResourceRequest request = request(range, method);
        return new DevMediaTrace(request, "test").response("dev-range",
                DevLocalMediaResponse.create(request, file,
                        "video/mp4", "https://player.example"), file);
    }
    // Emulate Chromium InputStreamReader: get full size, compute bounds, then
    // skip the absolute requested offset before reading the intercepted body.
    private byte[] chromiumBody(WebResourceResponse response, long start,
            long count, boolean head) throws Exception {
        assertFalse(response.getResponseHeaders().containsKey("Content-Length"));
        try (InputStream in = response.getData()) {
            assertEquals(LENGTH, in.available());
            long left = start;
            while (left > 0) {
                long skipped = in.skip(left);
                assertTrue("Chromium must be able to reach the offset", skipped > 0);
                left -= skipped;
            }
            if (head) {
                assertEquals(-1, in.read());
                return new byte[0];
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            assertEquals(count, out.size());
            assertEquals(0, in.available());
            return out.toByteArray();
        }
    }
    @Test public void byteZeroRangeHasSingleSynthesizedLengthAndExactBody() throws Exception {
        WebResourceResponse r = response("bytes=0-1023", "GET");
        assertEquals(206, r.getStatusCode());
        assertEquals("bytes 0-1023/" + LENGTH, r.getResponseHeaders().get("Content-Range"));
        chromiumBody(r, 0, 1024, false);
    }
    @Test public void laterByteRangeWorksAfterChromiumSeek() throws Exception {
        WebResourceResponse r = response("bytes=37000000-37001023", "GET");
        assertEquals("bytes 37000000-37001023/" + LENGTH, r.getResponseHeaders().get("Content-Range"));
        byte[] body = chromiumBody(r, LATER, 1024, false);
        assertArrayEquals(new byte[]{11, 22, 33, 44}, Arrays.copyOf(body, 4));
    }
    @Test public void openEndedRangeIncludesOnlyTheRemainingFile() throws Exception {
        chromiumBody(response("bytes=37000000-", "GET"), LATER, LENGTH - LATER, false);
    }
    @Test public void suffixRangeUsesFullFileSize() throws Exception {
        byte[] body = chromiumBody(response("bytes=-1024", "GET"), LENGTH - 1024, 1024, false);
        assertEquals(99, body[1023] & 255);
    }
    @Test public void oversizedSuffixAndWholeResponseAreComplete() throws Exception {
        chromiumBody(response("bytes=-999999999", "GET"), 0, LENGTH, false);
        WebResourceResponse r = response(null, "GET");
        assertEquals(200, r.getStatusCode());
        assertFalse(r.getResponseHeaders().containsKey("Content-Range"));
        chromiumBody(r, 0, LENGTH, false);
    }
    @Test public void closedRangeEndIsClampedAndUnitIsCaseInsensitive() throws Exception {
        chromiumBody(response(" BYTES=37999000-9999999999 ", "GET"), LENGTH - 1000, 1000, false);
    }
    @Test public void repeatedPlaybackAndTailToStartTransitionsUseFreshStreams() throws Exception {
        for (int loop = 0; loop < 6; loop++) {
            chromiumBody(response("bytes=0-1023", "GET"), 0, 1024, false);
            chromiumBody(response("bytes=37000000-37001023", "GET"), LATER, 1024, false);
            chromiumBody(response("bytes=-1024", "GET"), LENGTH - 1024, 1024, false);
            chromiumBody(response("bytes=0-1023", "GET"), 0, 1024, false);
        }
    }
    @Test public void headHasSizeMetadataButNoBody() throws Exception {
        chromiumBody(response("bytes=37000000-37001023", "HEAD"), LATER, 1024, true);
        chromiumBody(response(null, "HEAD"), 0, LENGTH, true);
    }
    @Test public void invalidRangesNeverExposeFileBytes() throws Exception {
        for (String range : new String[]{"bytes=38000000-", "bytes=8-2", "bytes=-0",
                "bytes=0-1,4-5", "bytes=99999999999999999999-", "bytes=-"}) {
            WebResourceResponse r = response(range, "GET");
            assertEquals(416, r.getStatusCode());
            assertEquals("bytes */" + LENGTH, r.getResponseHeaders().get("Content-Range"));
            try (InputStream in = r.getData()) { assertEquals(-1, in.read()); }
        }
    }
    @Test public void corsAndMethodRestrictionsArePreserved() throws Exception {
        WebResourceResponse r = response(null, "POST");
        assertEquals(405, r.getStatusCode());
        assertEquals("GET, HEAD", r.getResponseHeaders().get("Allow"));
        assertEquals("https://player.example", r.getResponseHeaders().get("Access-Control-Allow-Origin"));
        assertEquals("no-store", r.getResponseHeaders().get("Cache-Control"));
        assertEquals("nosniff", r.getResponseHeaders().get("X-Content-Type-Options"));
        assertFalse(r.getResponseHeaders().containsKey("Access-Control-Allow-Credentials"));
        r.getData().close();
    }
    @Test public void streamBoundsCloseAndZeroLengthReadsAreSafe() throws Exception {
        InputStream in = response("bytes=37000000-37001023", "GET").getData();
        assertEquals(0, in.skip(-1));
        assertEquals(LATER, in.skip(LATER));
        assertEquals(0, in.read(new byte[1], 0, 0));
        assertEquals(1024, in.available());
        assertEquals(1024, in.skip(Long.MAX_VALUE));
        assertEquals(-1, in.read());
        in.close();
        in.close();
        try { in.available(); fail("closed stream must fail"); } catch (IOException expected) {}
    }
    @Test public void emptyAndTruncatedFilesFailExplicitly() throws Exception {
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) { out.setLength(0); }
        try { response(null, "GET"); fail("empty file must fail"); } catch (IOException expected) {}
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) { out.setLength(LENGTH); }
        try (InputStream in = response("bytes=0-1023", "GET").getData()) {
            try (RandomAccessFile out = new RandomAccessFile(file, "rw")) { out.setLength(0); }
            try { in.read(); fail("truncation must fail"); } catch (IOException expected) {}
        }
    }
    @Test public void traceRecordsSelectionReadsSeekAndCloseWithoutPrivatePaths() throws Exception {
        ShadowLog.clear();
        chromiumBody(response("bytes=37000000-37001023", "GET"), LATER, 1024, false);
        StringBuilder log = new StringBuilder();
        for (ShadowLog.LogItem line : ShadowLog.getLogsForTag(DevMediaTrace.TAG)) log.append(line.msg).append('\n');
        String text = log.toString();
        assertTrue(text.contains("handler=dev-range status=206"));
        assertTrue(text.contains("range=bytes=37000000-37001023"));
        assertTrue(text.contains("skip offset=0 requested=37000000 actual=37000000"));
        assertTrue(text.contains("read offset=37000000"));
        assertTrue(text.contains("delivered=1024"));
        assertTrue(text.contains("close offset=37001024"));
        assertFalse(text.contains(file.getAbsolutePath()));
        assertFalse(text.contains("/media/test"));
        assertFalse(text.contains("Content-Length=1024"));
    }
    @Test public void traceRecordsReadFailureWithoutLeakingExceptionMessage() throws Exception {
        ShadowLog.clear();
        try (InputStream in = response("bytes=0-1023", "GET").getData()) {
            try (RandomAccessFile out = new RandomAccessFile(file, "rw")) { out.setLength(0); }
            try { in.read(); fail("must propagate the original read error"); }
            catch (IOException expected) { assertEquals("local-media-truncated", expected.getMessage()); }
        }
        StringBuilder log = new StringBuilder();
        for (ShadowLog.LogItem line : ShadowLog.getLogsForTag(DevMediaTrace.TAG)) log.append(line.msg).append('\n');
        assertTrue(log.toString().contains("exception operation=read offset=0"));
        assertTrue(log.toString().contains("fileSizeNow=0"));
        assertFalse(log.toString().contains(file.getAbsolutePath()));
        new DevMediaTrace(request(null, "GET"), "test").failure("test",
                new IOException("/private/path?token=secret-test-token"));
        for (ShadowLog.LogItem line : ShadowLog.getLogsForTag(DevMediaTrace.TAG)) {
            assertFalse(line.msg.contains("secret-test-token"));
            assertFalse(line.msg.contains("/private/path"));
        }
    }
}
