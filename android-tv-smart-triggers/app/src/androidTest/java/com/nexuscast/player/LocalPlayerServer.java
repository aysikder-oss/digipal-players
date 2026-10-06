package com.nexuscast.player;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Local-only emulator backend: never send test pairing/telemetry to production. */
final class LocalPlayerServer implements AutoCloseable {
    private final ServerSocket server;
    private final Thread worker;
    private volatile boolean closed;

    LocalPlayerServer() throws java.io.IOException {
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        worker = new Thread(() -> {
            while (!closed) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String request = reader.readLine();
                    if (request == null) continue;
                    String line;
                    int length = 0;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                            length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                        }
                    }
                    while (length-- > 0 && reader.read() != -1) {}
                    boolean json = request.contains(" /api/");
                    byte[] body = (json ? "{}" : "<html><body>Local ST test shell<script>"
                            + "setTimeout(function(){if(window.Android)Android.reportAppMounted()},0)"
                            + "</script></body></html>").getBytes(StandardCharsets.UTF_8);
                    String headers = "HTTP/1.1 200 OK\r\nContent-Type: "
                            + (json ? "application/json" : "text/html") + "\r\nContent-Length: "
                            + body.length + "\r\nConnection: close\r\n\r\n";
                    socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().write(body);
                    socket.getOutputStream().flush();
                } catch (Exception ignored) {
                    // A canceled client/closed test socket is expected on teardown.
                }
            }
        }, "ST-local-test-backend");
        worker.setDaemon(true);
        worker.start();
    }

    String origin() { return "http://127.0.0.1:" + server.getLocalPort(); }
    @Override public void close() throws java.io.IOException {
        closed = true;
        server.close();
    }
}
