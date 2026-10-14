package com.nexuscast.player;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;

/**
 * DEV-only adapter for Chromium's intercepted-stream range processing.
 * Chromium obtains the FULL source size from available(), then skips the
 * requested absolute offset itself. Pre-seeking here would apply it twice.
 * Do not emit Content-Length: Chromium generates it before appending headers.
 */
final class DevLocalMediaResponse {
    private DevLocalMediaResponse() {}

    static WebResourceResponse create(WebResourceRequest request, File file,
            String mime, String allowedOrigin) throws IOException {
        long length = file.length();
        if (length <= 0) throw new IOException("local-media-empty");
        // Chromium's intercepted InputStream size API is a signed 32-bit int.
        if (length > Integer.MAX_VALUE) throw new IOException("local-media-exceeds-webview-stream-limit");
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Ranges", "bytes");
        headers.put("Cache-Control", "no-store");
        headers.put("X-Content-Type-Options", "nosniff");
        if (allowedOrigin != null && !allowedOrigin.isEmpty()) {
            headers.put("Access-Control-Allow-Origin", allowedOrigin);
        }
        headers.put("Access-Control-Expose-Headers", "Content-Length,Content-Range,Accept-Ranges");
        String method = request.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            headers.put("Allow", "GET, HEAD");
            return empty(mime, 405, "Method Not Allowed", headers);
        }
        String range = null;
        Map<String, String> requestHeaders = request.getRequestHeaders();
        if (requestHeaders != null) {
            for (Map.Entry<String, String> header : requestHeaders.entrySet()) {
                if ("range".equalsIgnoreCase(header.getKey())) range = header.getValue();
            }
        }
        long start = 0;
        long end = length - 1;
        int status = 200;
        if (range != null) {
            try {
                range = range.trim();
                if (!range.matches("(?i)bytes=\\d*-\\d*")) throw new IllegalArgumentException();
                String[] limits = range.substring(6).split("-", -1);
                if (limits[0].isEmpty()) {
                    long suffix = Long.parseLong(limits[1]);
                    if (suffix <= 0) throw new IllegalArgumentException();
                    start = Math.max(0, length - suffix);
                } else {
                    start = Long.parseLong(limits[0]);
                    if (!limits[1].isEmpty()) end = Math.min(Long.parseLong(limits[1]), end);
                }
                if (start < 0 || start >= length || end < start) throw new IllegalArgumentException();
                status = 206;
                headers.put("Content-Range", "bytes " + start + "-" + end + "/" + length);
            } catch (RuntimeException invalid) {
                headers.put("Content-Range", "bytes */" + length);
                return empty(mime, 416, "Range Not Satisfiable", headers);
            }
        }
        InputStream stream = new ChromiumRangeStream(file, length, end + 1,
                "HEAD".equalsIgnoreCase(method));
        return new WebResourceResponse(mime, null, status,
                status == 206 ? "Partial Content" : "OK", headers, stream);
    }

    private static WebResourceResponse empty(String mime, int status, String reason,
            Map<String, String> headers) {
        return new WebResourceResponse(mime, null, status, reason, headers,
                new ByteArrayInputStream(new byte[0]));
    }

    private static final class ChromiumRangeStream extends InputStream {
        private final RandomAccessFile source;
        private final long fullLength;
        private final long limit;
        private final boolean head;
        private long position;
        private boolean closed;

        ChromiumRangeStream(File file, long fullLength, long limit, boolean head)
                throws IOException {
            this.source = new RandomAccessFile(file, "r");
            this.fullLength = fullLength;
            this.limit = limit;
            this.head = head;
        }

        private void checkOpen() throws IOException {
            if (closed) throw new IOException("local-media-stream-closed");
        }

        @Override public int available() throws IOException {
            checkOpen();
            // Before Chromium's seek, it validates the original Range against
            // the full file size. Afterwards, only the bounded body remains.
            return (int) (position == 0 ? fullLength : Math.max(0, limit - position));
        }

        @Override public long skip(long count) throws IOException {
            checkOpen();
            if (count <= 0) return 0;
            long skipped = Math.min(count, Math.max(0, limit - position));
            source.seek(position + skipped);
            position += skipped;
            return skipped;
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
        }

        @Override public int read(byte[] buffer, int offset, int count) throws IOException {
            checkOpen();
            if (buffer == null) throw new NullPointerException("buffer");
            if (offset < 0 || count < 0 || offset > buffer.length - count) {
                throw new IndexOutOfBoundsException();
            }
            if (count == 0) return 0;
            if (head || position >= limit) return -1;
            int read = source.read(buffer, offset, (int) Math.min(count, limit - position));
            if (read == -1) throw new IOException("local-media-truncated");
            position += read;
            return read;
        }

        @Override public void close() throws IOException {
            if (!closed) {
                closed = true;
                source.close();
            }
        }
    }
}
