package com.digipal.offline;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import java.io.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Local HTTPS media using Chromium's intercepted InputStream contract.
 * Chromium obtains the full size from available() and applies the absolute
 * Range offset through skip(). Pre-seeking applies that offset twice.
 */
public final class LocalMediaResponse {
    private LocalMediaResponse() {}
    public static String originOf(String address) {
        try {
            java.net.URL url = new java.net.URL(address);
            if (url.getUserInfo() != null || !("https".equals(url.getProtocol()) || "http".equals(url.getProtocol()))) return "";
            return url.getProtocol() + "://" + url.getAuthority();
        } catch (Exception ignored) { return ""; }
    }
    public static WebResourceResponse create(WebResourceRequest request, File file, String mime, String allowedOrigin) throws IOException {
        long length = file.length();
        if (length <= 0) throw new IOException("local-media-empty");
        if (length > Integer.MAX_VALUE) throw new IOException("local-media-exceeds-webview-stream-limit");
        String range = null;
        if (request.getRequestHeaders() != null) {
            for (Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
                if ("range".equalsIgnoreCase(header.getKey())) range = header.getValue();
            }
        }
        long start = 0, end = length - 1;
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Ranges", "bytes");
        headers.put("Cache-Control", "no-store");
        headers.put("X-Content-Type-Options", "nosniff");
        if (allowedOrigin != null && !allowedOrigin.isEmpty()) headers.put("Access-Control-Allow-Origin", allowedOrigin);
        headers.put("Access-Control-Expose-Headers", "Content-Length,Content-Range,Accept-Ranges");
        String method = request.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            headers.put("Allow", "GET, HEAD");
            return new WebResourceResponse(mime, null, 405, "Method Not Allowed", headers,
                    new ByteArrayInputStream(new byte[0]));
        }
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
                    if (!limits[1].isEmpty()) end = Math.min(Long.parseLong(limits[1]), length - 1);
                }
                if (start < 0 || start >= length || end < start) throw new IllegalArgumentException();
                status = 206;
                headers.put("Content-Range", "bytes " + start + "-" + end + "/" + length);
            } catch (RuntimeException invalid) {
                headers.put("Content-Range", "bytes */" + length);
                return new WebResourceResponse(mime, null, 416, "Range Not Satisfiable", headers, new ByteArrayInputStream(new byte[0]));
            }
        }
        // Do not append Content-Length: Chromium generates it from available().
        // A second header produced "0, 1024" and failed subsequent video ranges.
        final RandomAccessFile source = new RandomAccessFile(file, "r");
        final long fullLength = length;
        final long limit = end + 1;
        final boolean head = "HEAD".equalsIgnoreCase(method);
        InputStream stream = new InputStream() {
            long position;
            boolean closed;
            private void checkOpen() throws IOException {
                if (closed) throw new IOException("local-media-stream-closed");
            }
            @Override public int available() throws IOException {
                checkOpen();
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
            @Override public int read(byte[] buffer, int offset, int size) throws IOException {
                checkOpen();
                if (buffer == null) throw new NullPointerException("buffer");
                if (offset < 0 || size < 0 || offset > buffer.length - size)
                    throw new IndexOutOfBoundsException();
                if (size == 0) return 0;
                if (head || position >= limit) return -1;
                int read = source.read(buffer, offset, (int) Math.min(size, limit - position));
                if (read == -1) throw new IOException("local-media-truncated");
                position += read;
                return read;
            }
            @Override public void close() throws IOException {
                if (!closed) { closed = true; source.close(); }
            }
        };
        return new WebResourceResponse(mime, null, status, status == 206 ? "Partial Content" : "OK", headers, stream);
    }
}
