package com.digipal.offline;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import java.io.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LocalMediaResponseTest {
    File file;
    @Before public void createFile() throws Exception {
        file = File.createTempFile("local-media", ".bin");
        try (FileOutputStream out = new FileOutputStream(file)) { out.write("0123456789".getBytes("UTF-8")); }
    }
    @After public void cleanup() { file.delete(); }
    WebResourceRequest request(final String range, final String method) {
        return new WebResourceRequest() {
            public Uri getUrl() { return Uri.parse("https://appassets.androidplatform.net/media/key"); }
            public boolean isForMainFrame() { return false; }
            public boolean isRedirect() { return false; }
            public boolean hasGesture() { return false; }
            public String getMethod() { return method; }
            public Map<String,String> getRequestHeaders() {
                return range == null ? Collections.emptyMap() : Collections.singletonMap("Range", range);
            }
        };
    }
    String body(WebResourceResponse response) throws Exception {
        try (InputStream in = response.getData(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // Model Chromium's absolute range seek rather than pre-seeking twice.
            String range = response.getResponseHeaders().get("Content-Range");
            if (response.getStatusCode() == 206 && range != null) {
                long offset = Long.parseLong(range.substring(6, range.indexOf('-')));
                assertEquals(offset, in.skip(offset));
            }
            int value; while ((value = in.read()) != -1) out.write(value);
            return out.toString("UTF-8");
        }
    }
    @Test public void returnsCompleteBodyWithLengthAndRestrictedCors() throws Exception {
        WebResourceResponse result = LocalMediaResponse.create(request(null,"GET"), file,"video/mp4","https://player.example");
        assertEquals(200,result.getStatusCode());
        assertFalse(result.getResponseHeaders().containsKey("Content-Length"));
        assertEquals(10, result.getData().available());
        assertEquals("https://player.example",result.getResponseHeaders().get("Access-Control-Allow-Origin"));
        assertEquals("0123456789",body(result));
    }
    @Test public void supportsClosedOpenAndSuffixRanges() throws Exception {
        WebResourceResponse result = LocalMediaResponse.create(request("bytes=2-4","GET"),file,"video/mp4","");
        assertEquals(206,result.getStatusCode());
        assertEquals("bytes 2-4/10",result.getResponseHeaders().get("Content-Range"));
        assertEquals("234",body(result));
        assertEquals("789",body(LocalMediaResponse.create(request("bytes=7-","GET"),file,"video/mp4","")));
        assertEquals("89",body(LocalMediaResponse.create(request("bytes=-2","GET"),file,"video/mp4","")));
    }
    @Test public void refusesInvalidRangesWithoutOpeningTheFile() throws Exception {
        for (String range : new String[]{"bytes=10-","bytes=8-2","bytes=-0","bytes=0-1,4-5","bytes=99999999999999999999-","bytes=-"}) {
            WebResourceResponse result = LocalMediaResponse.create(request(range,"GET"),file,"video/mp4","");
            assertEquals(416,result.getStatusCode());
            assertEquals("",body(result));
        }
    }
    @Test public void headDoesNotReadBodyAndCorsRejectsCredentials() throws Exception {
        WebResourceResponse result = LocalMediaResponse.create(request(null,"HEAD"),file,"application/pdf","");
        assertFalse(result.getResponseHeaders().containsKey("Content-Length"));
        assertEquals("",body(result));
        assertEquals("",LocalMediaResponse.originOf("https://user:password@example.test"));
    }
    @Test public void laterRangesAndRepeatedRestartUseIndependentStreams() throws Exception {
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            out.setLength(38_000_000);
            out.seek(37_000_000);
            out.write(new byte[]{11,22,33,44});
        }
        for (int loop = 0; loop < 4; loop++) {
            WebResourceResponse result = LocalMediaResponse.create(
                    request("bytes=37000000-37000003","GET"), file, "video/mp4", "");
            assertEquals(206, result.getStatusCode());
            assertFalse(result.getResponseHeaders().containsKey("Content-Length"));
            try (InputStream in = result.getData()) {
                assertEquals(38_000_000, in.available());
                assertEquals(37_000_000, in.skip(37_000_000));
                assertEquals(4, in.available());
                assertEquals(11, in.read());
                assertEquals(22, in.read());
                assertEquals(33, in.read());
                assertEquals(44, in.read());
                assertEquals(-1, in.read());
            }
            try (InputStream in = LocalMediaResponse.create(
                    request("bytes=0-1023","GET"),file,"video/mp4","").getData()) {
                assertEquals(38_000_000, in.available());
                assertEquals((int) '0', in.read());
            }
        }
    }
}
