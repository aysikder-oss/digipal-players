package com.digipal.offline;

import java.net.HttpURLConnection;
import java.net.URL;

/** Check the server itself, not merely the Wi-Fi link. Never run on the UI thread. */
public final class ServerReachability {
    private ServerReachability() {}
    public static boolean canLoadPlayer(String server) {
        HttpURLConnection connection = null;
        try {
            String origin = LocalMediaResponse.originOf(server);
            if (origin == null) return false;
            connection = (HttpURLConnection) new URL(origin + "/player?platform=android_tv").openConnection();
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(1500);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "text/html");
            return connection.getResponseCode() == 200
                    && connection.getContentType() != null
                    && connection.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("text/html");
        } catch (Exception unavailable) { return false; }
        finally { if (connection != null) connection.disconnect(); }
    }
}
