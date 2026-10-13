package com.digipal.offline;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import java.io.*;
import java.util.HashMap;
import java.util.Map;

/** Seekable local HTTPS media for WebView video/PDF/font consumers; no raw file URL. */
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
        String range = null;
        for (Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
            if ("range".equalsIgnoreCase(header.getKey())) range = header.getValue();
        }
        long start = 0, end = length - 1;
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Ranges", "bytes");
        headers.put("Cache-Control", "no-store");
        headers.put("X-Content-Type-Options", "nosniff");
        if (allowedOrigin != null && !allowedOrigin.isEmpty()) headers.put("Access-Control-Allow-Origin", allowedOrigin);
        headers.put("Access-Control-Expose-Headers", "Content-Length,Content-Range,Accept-Ranges");
        int status = 200;
        if (range != null) {
            try {
                if (!range.matches("bytes=\\d*-\\d*")) throw new IllegalArgumentException();
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
                headers.put("Content-Length", "0");
                return new WebResourceResponse(mime, null, 416, "Range Not Satisfiable", headers, new ByteArrayInputStream(new byte[0]));
            }
        }
        long count = end - start + 1;
        headers.put("Content-Length", Long.toString(count));
        if ("HEAD".equalsIgnoreCase(request.getMethod())) {
            return new WebResourceResponse(mime, null, status, status == 206 ? "Partial Content" : "OK", headers, new ByteArrayInputStream(new byte[0]));
        }
        final RandomAccessFile source = new RandomAccessFile(file, "r");
        source.seek(start);
        final long available = count;
        InputStream stream = new InputStream() {
            long remaining = available;
            @Override public int read() throws IOException {
                if (remaining == 0) return -1;
                int value = source.read();
                if (value >= 0) remaining--;
                return value;
            }
            @Override public int read(byte[] buffer, int offset, int size) throws IOException {
                if (size == 0) return 0;
                if (remaining == 0) return -1;
                int read = source.read(buffer, offset, (int) Math.min(size, remaining));
                if (read > 0) remaining -= read;
                return read;
            }
            @Override public void close() throws IOException { source.close(); }
        };
        return new WebResourceResponse(mime, null, status, status == 206 ? "Partial Content" : "OK", headers, stream);
    }
}
