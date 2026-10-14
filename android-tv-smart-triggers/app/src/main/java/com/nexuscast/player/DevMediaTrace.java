package com.nexuscast.player;

import android.os.SystemClock;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Observes DEV streams without changing response headers, positioning or bytes. */
final class DevMediaTrace {
    static final String TAG = "DigipalMediaTrace";
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final AtomicInteger LINES = new AtomicInteger();
    private static final AtomicInteger ERRORS = new AtomicInteger();
    private final int id = IDS.incrementAndGet();
    private final long started = SystemClock.elapsedRealtime();
    private long length = -1;

    DevMediaTrace(WebResourceRequest request, String renderer) {
        String path = request.getUrl().getEncodedPath();
        String range = null;
        if (request.getRequestHeaders() != null) {
            for (Map.Entry<String, String> h : request.getRequestHeaders().entrySet()) {
                if ("range".equalsIgnoreCase(h.getKey())) range = h.getValue();
            }
        }
        // No query strings, object paths, auth headers or filesystem paths.
        event("request renderer=" + renderer + " method=" + safe(request.getMethod())
                + " url=https://appassets.androidplatform.net/media/<" + fingerprint(path)
                + "> range=" + safe(range), false);
    }

    static String fingerprint(String path) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    String.valueOf(path).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 6; i++) out.append(String.format(java.util.Locale.ROOT, "%02x", digest[i]));
            return out.toString();
        } catch (Exception ignored) { return "unavailable"; }
    }

    private static String safe(String value) {
        if (value == null) return "none";
        return value.replaceAll("[^a-zA-Z0-9=*/, .:_-]", "?")
                .substring(0, Math.min(value.length(), 160));
    }

    void event(String detail, boolean error) {
        if (!"offlineTest".equals(BuildConfig.BUILD_TYPE)) return;
        int count = (error ? ERRORS : LINES).incrementAndGet();
        int limit = error ? 200 : 2000;
        if (count > limit) return;
        String line = "id=" + id + " elapsedMs=" + (SystemClock.elapsedRealtime() - started)
                + " " + detail;
        if (error) Log.w(TAG, line); else Log.i(TAG, line);
        if (count == limit) Log.w(TAG, "trace-budget-exhausted channel=" + (error ? "error" : "info"));
    }

    void failure(String operation, Throwable error) {
        StringBuilder frames = new StringBuilder();
        StackTraceElement[] stack = error.getStackTrace();
        for (int i = 0; i < Math.min(6, stack.length); i++) {
            frames.append(" ").append(stack[i].getClassName()).append(".")
                    .append(stack[i].getMethodName()).append(":").append(stack[i].getLineNumber());
        }
        // Exception messages can contain private filesystem paths.
        event("exception operation=" + operation + " type=" + error.getClass().getName()
                + " frames=" + frames, true);
    }

    WebResourceResponse response(String handler, WebResourceResponse response, File file) {
        length = file.length();
        Map<String, String> headers = response.getResponseHeaders();
        event("response handler=" + handler + " status=" + response.getStatusCode()
                + " fileSize=" + length + " mime=" + safe(response.getMimeType())
                + " javaContentLength=" + header(headers, "Content-Length")
                + " contentRange=" + header(headers, "Content-Range")
                + " acceptRanges=" + header(headers, "Accept-Ranges")
                + " cacheControl=" + header(headers, "Cache-Control")
                + " corsOrigin=" + header(headers, "Access-Control-Allow-Origin")
                + " nosniff=" + header(headers, "X-Content-Type-Options")
                + " lengthNote=Chromium-may-synthesize-length", false);
        if (response.getData() != null) response.setData(new ObservedStream(response.getData(), file));
        return response;
    }

    private static String header(Map<String, String> headers, String key) {
        return safe(headers == null ? null : headers.get(key));
    }

    private final class ObservedStream extends InputStream {
        private final InputStream delegate;
        private final File file;
        private long offset;
        private long delivered;
        private long nextReport = 4 * 1024 * 1024;
        private int availabilityCalls;
        private int reads;
        private boolean eof;

        ObservedStream(InputStream delegate, File file) { this.delegate = delegate; this.file = file; }
        @Override public int available() throws IOException {
            try {
                int result = delegate.available();
                if (++availabilityCalls <= 2) event("available offset=" + offset + " value=" + result, false);
                return result;
            } catch (IOException | RuntimeException e) { failure("available offset=" + offset, e); throw e; }
        }
        @Override public long skip(long count) throws IOException {
            try {
                long skipped = delegate.skip(count);
                event("skip offset=" + offset + " requested=" + count + " actual=" + skipped, false);
                offset += skipped;
                return skipped;
            } catch (IOException | RuntimeException e) { failure("skip offset=" + offset + " requested=" + count, e); throw e; }
        }
        private void recordRead(long before, int requested, int actual) {
            if (actual > 0) { offset += actual; delivered += actual; }
            if (++reads == 1 || delivered >= nextReport || (actual == -1 && !eof)) {
                event("read offset=" + before + " requested=" + requested + " actual=" + actual
                        + " delivered=" + delivered + " fileSizeNow=" + file.length(), false);
                nextReport = delivered + 4 * 1024 * 1024;
            }
            if (actual == -1) eof = true;
        }
        @Override public int read() throws IOException {
            long before = offset;
            try { int value = delegate.read(); recordRead(before, 1, value < 0 ? -1 : 1); return value; }
            catch (IOException | RuntimeException e) { failure("read offset=" + offset + " delivered=" + delivered + " fileSizeNow=" + file.length(), e); throw e; }
        }
        @Override public int read(byte[] data, int start, int count) throws IOException {
            long before = offset;
            try { int value = delegate.read(data, start, count); recordRead(before, count, value); return value; }
            catch (IOException | RuntimeException e) { failure("read offset=" + offset + " requested=" + count + " delivered=" + delivered + " fileSizeNow=" + file.length(), e); throw e; }
        }
        @Override public boolean markSupported() { return delegate.markSupported(); }
        @Override public void mark(int limit) { delegate.mark(limit); event("mark limit=" + limit + " offset=" + offset, false); }
        @Override public void reset() throws IOException {
            try { delegate.reset(); event("reset offsetEstimateBefore=" + offset, false); }
            catch (IOException | RuntimeException e) { failure("reset", e); throw e; }
        }
        @Override public void close() throws IOException {
            event("close offset=" + offset + " delivered=" + delivered + " eof=" + eof
                    + " initialFileSize=" + length + " fileSizeNow=" + file.length(), false);
            try { delegate.close(); }
            catch (IOException | RuntimeException e) { failure("close", e); throw e; }
        }
    }
}
