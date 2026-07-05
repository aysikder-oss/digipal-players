package com.digipal.signage;

  import static org.junit.Assert.assertEquals;
  import static org.junit.Assert.assertFalse;
  import static org.junit.Assert.assertNotNull;
  import static org.junit.Assert.assertNull;
  import static org.junit.Assert.assertTrue;

  import java.io.File;
  import java.io.FileWriter;
  import java.io.IOException;

  import org.junit.After;
  import org.junit.Before;
  import org.junit.Test;

  public class SafeFilesTest {

      private File root;
      private File outsideDir;

      @Before
      public void setUp() throws IOException {
          root = File.createTempFile("safefiles-root", "");
          assertTrue(root.delete());
          assertTrue(root.mkdirs());

          outsideDir = File.createTempFile("safefiles-outside", "");
          assertTrue(outsideDir.delete());
          assertTrue(outsideDir.mkdirs());
      }

      @After
      public void tearDown() {
          deleteRecursively(root);
          deleteRecursively(outsideDir);
      }

      private void deleteRecursively(File f) {
          if (f == null || !f.exists()) return;
          File[] children = f.listFiles();
          if (children != null) {
              for (File c : children) deleteRecursively(c);
          }
          f.delete();
      }

      @Test
      public void childResolvesSimpleRelativeName() throws IOException {
          File child = SafeFiles.child(root, "asset.bin");
          assertTrue(SafeFiles.isInside(root, child));
      }

      @Test(expected = SecurityException.class)
      public void childRejectsPathTraversal() throws IOException {
          SafeFiles.child(root, "../escape.bin");
      }

      @Test(expected = SecurityException.class)
      public void childRejectsAbsoluteEscape() throws IOException {
          SafeFiles.child(root, "../../../../etc/passwd");
      }

      @Test
      public void isInsideTrueForNestedPath() throws IOException {
          File nested = new File(new File(root, "sub"), "file.txt");
          assertTrue(SafeFiles.isInside(root, nested));
      }

      @Test
      public void isInsideFalseForSiblingDirectory() throws IOException {
          File sibling = new File(outsideDir, "file.txt");
          assertFalse(SafeFiles.isInside(root, sibling));
      }

      @Test
      public void existingFileInsideOrNullReturnsNullWhenMissing() {
          assertNull(SafeFiles.existingFileInsideOrNull(root, new File(root, "missing.txt").getPath()));
      }

      @Test
      public void existingFileInsideOrNullReturnsNullForOutsidePath() throws IOException {
          File outsideFile = new File(outsideDir, "secret.txt");
          try (FileWriter w = new FileWriter(outsideFile)) {
              w.write("data");
          }
          assertNull(SafeFiles.existingFileInsideOrNull(root, outsideFile.getPath()));
      }

      @Test
      public void existingFileInsideOrNullReturnsFileWhenValid() throws IOException {
          File insideFile = new File(root, "ok.txt");
          try (FileWriter w = new FileWriter(insideFile)) {
              w.write("data");
          }
          File result = SafeFiles.existingFileInsideOrNull(root, insideFile.getPath());
          assertNotNull(result);
          assertEquals(insideFile.getCanonicalFile(), result.getCanonicalFile());
      }

      @Test
      public void existingFileInsideOrNullHandlesNullAndEmptyInputs() {
          assertNull(SafeFiles.existingFileInsideOrNull(null, "/tmp/x"));
          assertNull(SafeFiles.existingFileInsideOrNull(root, null));
          assertNull(SafeFiles.existingFileInsideOrNull(root, ""));
      }

      @Test
      public void deleteFileInsideDeletesOnlyWithinRoot() throws IOException {
          File insideFile = new File(root, "deleteme.txt");
          try (FileWriter w = new FileWriter(insideFile)) {
              w.write("data");
          }
          assertTrue(SafeFiles.deleteFileInside(root, insideFile.getPath()));
          assertFalse(insideFile.exists());

          File outsideFile = new File(outsideDir, "keepme.txt");
          try (FileWriter w = new FileWriter(outsideFile)) {
              w.write("data");
          }
          assertFalse(SafeFiles.deleteFileInside(root, outsideFile.getPath()));
          assertTrue(outsideFile.exists());
      }
  }
  