package com.digipal.signage;

  import android.net.Uri;
  import java.util.Locale;
  import java.util.regex.Pattern;

  final class UrlPolicy {
      private static final Pattern PRIVATE_IPV4 = Pattern.compile(
              "^(10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"
                      + "|192\\.168\\.\\d{1,3}\\.\\d{1,3}"
                      + "|172\\.(1[6-9]|2\\d|3[0-1])\\.\\d{1,3}\\.\\d{1,3}"
                      + "|127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})$");

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
                  || h.startsWith("fc")
                  || h.startsWith("fd")
                  || h.startsWith("fe80:")
                  || PRIVATE_IPV4.matcher(h).matches();
      }
  }
  