package com.nexuscast.player;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * URL admission only; successful download is not proof that Glide decoded/drew an image.
 * Digipal's upload IDs are opaque UUIDs, NOT filenames with raster extensions.
 */
final class NativeImageUrlPolicy {
    private static final String UUID =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern UPLOADED_OBJECT = Pattern.compile(
            "/replit-objstore-" + UUID + "/\\.private/uploads/" + UUID);

    private NativeImageUrlPolicy() {}

    static boolean isUnsafe(String url) {
        if (url == null || url.trim().isEmpty()) return true;
        String path = url.indexOf('?') >= 0 ? url.substring(0, url.indexOf('?')) : url;
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".webm")
                || lower.endsWith(".avi") || lower.endsWith(".m4v") || lower.endsWith(".mkv")
                || lower.endsWith(".ts") || lower.endsWith(".svg")) {
            return true;
        }
        if ((lower.startsWith("http://") || lower.startsWith("https://"))
                && !lower.contains("/objects/")) {
            // These are the signed form of our existing /objects/uploads/<UUID>
            // images. Do not broaden this to every GCS URL, arbitrary extensionless
            // external URL, or appassets URL (which only a WebView can intercept).
            if (isUploadedObject(url)) return false;
            int slash = lower.lastIndexOf('/');
            String segment = slash >= 0 ? lower.substring(slash + 1) : lower;
            int dot = segment.lastIndexOf('.');
            if (dot < 0) return true;
            String ext = segment.substring(dot);
            return !(ext.equals(".jpg") || ext.equals(".jpeg") || ext.equals(".png")
                    || ext.equals(".webp") || ext.equals(".gif") || ext.equals(".bmp")
                    || ext.equals(".avif"));
        }
        return false; // Preserve canonical /objects/ and native file/content sources.
    }

    private static boolean isUploadedObject(String url) {
        try {
            URI uri = new URI(url);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "storage.googleapis.com".equalsIgnoreCase(uri.getHost())
                    && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getRawFragment() == null
                    && UPLOADED_OBJECT.matcher(uri.getRawPath()).matches();
        } catch (Exception ignored) {
            return false;
        }
    }
}
