package com.nexuscast.player;

    import android.net.Uri;
    import java.util.Locale;
    import java.util.regex.Pattern;

    final class UrlPolicy {
        // Each IPv4 octet must be 0-255 (not an unranged \\d{1,3}, which would accept
        // invalid values like 999).
        private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
        private static final Pattern PRIVATE_IPV4 = Pattern.compile(
                "^(10\\." + OCTET + "\\." + OCTET + "\\." + OCTET
                        + "|192\\.168\\." + OCTET + "\\." + OCTET
                        + "|172\\.(1[6-9]|2\\d|3[0-1])\\." + OCTET + "\\." + OCTET
                        + "|127\\." + OCTET + "\\." + OCTET + "\\." + OCTET + ")$");

        // IPv6 Unique Local Address prefix is fc00::/7 — i.e. the first hextet's first byte
        // is 0xfc or 0xfd, always followed by more hex digits and a ':'. A bare
        // host.startsWith("fc")/("fd") also matches public hostnames like "fd-example.com",
        // so require the "fc"/"fd" prefix to actually be followed by hex digits and a colon.
        private static final Pattern IPV6_UNIQUE_LOCAL = Pattern.compile(
                "^f[cd][0-9a-f]{0,2}:.*$");

        private UrlPolicy() {}

        static boolean isAllowedServerUrl(String rawUrl) {
            if (rawUrl == null) return false;
            try {
                Uri uri = Uri.parse(rawUrl.trim());
                String scheme = uri.getScheme();
                String host = uri.getHost();
                if (scheme == null || host == null || host.isEmpty()) return false;
                if (uri.getUserInfo() != null) return false;
                if ("https".equalsIgnoreCase(scheme)) return true;
                return "http".equalsIgnoreCase(scheme) && isPrivateHost(host);
            } catch (Throwable t) {
                return false;
            }
        }

        static boolean isPrivateHost(String host) {
            if (host == null) return false;
            String h = host.trim().toLowerCase(Locale.ROOT);
            if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) {
                h = h.substring(1, h.length() - 1);
            }
            return "localhost".equals(h)
                    || "::1".equals(h)
                    || h.startsWith("fe80:")
                    || IPV6_UNIQUE_LOCAL.matcher(h).matches()
                    || PRIVATE_IPV4.matcher(h).matches();
        }
    }
    