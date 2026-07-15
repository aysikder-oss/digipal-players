package com.nexuscast.player;

  import java.io.File;
  import java.io.IOException;

  final class SafeFiles {
      private SafeFiles() {}

      static File child(File root, String name) throws IOException {
          File base = root.getCanonicalFile();
          File child = new File(base, name).getCanonicalFile();
          if (!isInside(base, child)) {
              throw new SecurityException("Path escapes cache root: " + name);
          }
          return child;
      }

      static boolean isInside(File root, File file) throws IOException {
          String base = root.getCanonicalFile().getPath();
          String path = file.getCanonicalFile().getPath();
          return path.equals(base) || path.startsWith(base + File.separator);
      }

      static File existingFileInsideOrNull(File root, String path) {
          if (root == null || path == null || path.isEmpty()) return null;
          try {
              File file = new File(path);
              if (!file.exists() || !file.isFile()) return null;
              return isInside(root, file) ? file : null;
          } catch (Throwable t) {
              return null;
          }
      }

      static boolean deleteFileInside(File root, String path) {
          File file = existingFileInsideOrNull(root, path);
          return file != null && file.delete();
      }
  }
  