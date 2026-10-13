package com.digipal.offline;

import java.io.File;
import java.util.Locale;

/** Validate a staged transfer before replacing any usable existing file. */
public final class DownloadIntegrity {
    private DownloadIntegrity() {}

    public static long declaredLength(String header) {
        if (header == null) return -1;
        try {
            long value = Long.parseLong(header.trim());
            return value >= 0 ? value : -2;
        } catch (NumberFormatException invalid) { return -2; }
    }

    public static boolean isComplete(File staged, long bytesRead, long declaredLength, String contentType) {
        if (declaredLength < -1) return false;
        if (staged == null || !staged.isFile() || bytesRead <= 0 || staged.length() != bytesRead) return false;
        if (declaredLength >= 0 && bytesRead != declaredLength) return false;
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        return !type.equals("text/html") && !type.equals("application/xhtml+xml");
    }

    /** Missing length is supported for legacy entries; a known length is exact. */
    public static boolean isAvailable(File file, long expectedBytes) {
        if (file == null || !file.isFile() || file.length() <= 0) return false;
        return expectedBytes == -1 || (expectedBytes > 0 && file.length() == expectedBytes);
    }
}
