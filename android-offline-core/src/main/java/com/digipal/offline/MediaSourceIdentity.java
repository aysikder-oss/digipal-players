package com.digipal.offline;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Candidate-only identity: auth renewal is not a content version change. */
public final class MediaSourceIdentity {
    private static final Set<String> AUTH_KEYS = new HashSet<>(Arrays.asList(
        "signature", "expires", "googleaccessid", "awsaccesskeyid",
        "x-goog-algorithm", "x-goog-credential", "x-goog-date",
        "x-goog-expires", "x-goog-signedheaders", "x-goog-signature",
        "x-amz-algorithm", "x-amz-credential", "x-amz-date",
        "x-amz-expires", "x-amz-signedheaders", "x-amz-signature",
        "x-amz-security-token"
    ));

    private MediaSourceIdentity() {}

    public static boolean matches(String previous, String requested) {
        String a = canonical(previous), b = canonical(requested);
        return !a.isEmpty() && !b.isEmpty() && a.equals(b);
    }

    private static String canonical(String value) {
        try {
            if (value == null || value.trim().isEmpty()) return "";
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null || uri.getRawUserInfo() != null) return "";
            scheme = scheme.toLowerCase(Locale.US);
            if (!scheme.equals("https") && !scheme.equals("http")) return "";
            int port = uri.getPort();
            if ((scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80)) port = -1;
            List<String> query = new ArrayList<>();
            if (uri.getRawQuery() != null) {
                for (String part : uri.getRawQuery().split("&", -1)) {
                    String key = part.split("=", 2)[0].toLowerCase(Locale.US);
                    if (!AUTH_KEYS.contains(key)) query.add(part);
                }
            }
            // Keep query order: third-party endpoints may consider it meaningful.
            StringBuilder suffix = new StringBuilder();
            for (String part : query) suffix.append(suffix.length() == 0 ? "?" : "&").append(part);
            return scheme + "://" + uri.getHost().toLowerCase(Locale.US)
                + (port >= 0 ? ":" + port : "")
                + (uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                + suffix;
        } catch (Exception invalid) {
            return "";
        }
    }
}
