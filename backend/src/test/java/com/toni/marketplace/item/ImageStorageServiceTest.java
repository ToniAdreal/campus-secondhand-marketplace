package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import com.toni.marketplace.common.InvalidImageException;

/**
 * Unit tests for {@link ImageStorageService} without a Spring context:
 * validation rules and the UUID-filename contract are exercised directly.
 */
class ImageStorageServiceTest {

  @TempDir
  private Path tmpDir;

  private Path uploadDir;
  private ImageStorageService storage;

  @BeforeEach
  void setUp() throws IOException {
    uploadDir = tmpDir.resolve("uploads");
    storage = new ImageStorageService(uploadDir.toString(), DataSize.ofMegabytes(5));
  }

  @AfterEach
  void wipe() throws IOException {
    if (Files.exists(uploadDir)) {
      try (var stream = Files.walk(uploadDir)) {
        stream.sorted(Comparator.reverseOrder())
            .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
      }
    }
  }

  private static final byte[] PNG_BYTES = {
      (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01};
  private static final byte[] JPEG_BYTES = {
      (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};
  private static final byte[] GIF87_BYTES = "GIF87a..".getBytes();
  private static final byte[] GIF89_BYTES = "GIF89a..".getBytes();
  private static final byte[] WEBP_BYTES = {
      'R', 'I', 'F', 'F', 0x10, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P', 'V'};

  @Test
  void storesFileWithUuidNameAndContentTypeExtension() {
    byte[] bytes = PNG_BYTES;
    MockMultipartFile file =
        new MockMultipartFile("file", "../../etc/evil.jsp", "image/png", bytes);

    String url = storage.store(file);

    // UUID filename — the hostile client filename never reaches the disk.
    assertThat(url).matches("/uploads/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}"
        + "-[0-9a-f]{4}-[0-9a-f]{12}\\.png");
    assertThat(url).doesNotContain("evil");
    assertThat(uploadDir.resolve(url.substring("/uploads/".length())))
        .exists().binaryContent().isEqualTo(bytes);
  }

  @Test
  void rejectsUnsupportedContentType() {
    MockMultipartFile file =
        new MockMultipartFile("file", "x.svg", "image/svg+xml", new byte[]{1, 2});

    assertThatThrownBy(() -> storage.store(file))
        .isInstanceOf(InvalidImageException.class)
        .hasMessageContaining("unsupported image type");
  }

  @Test
  void rejectsOversizedFile() throws IOException {
    ImageStorageService tiny =
        new ImageStorageService(tmpDir.resolve("tiny").toString(), DataSize.ofBytes(10));
    MockMultipartFile file =
        new MockMultipartFile("file", "x.png", "image/png", new byte[1024]);

    assertThatThrownBy(() -> tiny.store(file))
        .isInstanceOf(InvalidImageException.class)
        .hasMessageContaining("image too large");
  }

  @Test
  void rejectsEmptyFile() {
    MockMultipartFile file =
        new MockMultipartFile("file", "x.png", "image/png", new byte[0]);

    assertThatThrownBy(() -> storage.store(file))
        .isInstanceOf(InvalidImageException.class)
        .hasMessageContaining("image file is required");
  }

  @Test
  void honestBytesForEachAllowlistedTypePass() {
    assertThat(storage.store(new MockMultipartFile(
        "file", "a.png", "image/png", PNG_BYTES))).endsWith(".png");
    assertThat(storage.store(new MockMultipartFile(
        "file", "a.jpg", "image/jpeg", JPEG_BYTES))).endsWith(".jpg");
    assertThat(storage.store(new MockMultipartFile(
        "file", "a.gif", "image/gif", GIF87_BYTES))).endsWith(".gif");
    assertThat(storage.store(new MockMultipartFile(
        "file", "a.gif", "image/gif", GIF89_BYTES))).endsWith(".gif");
    assertThat(storage.store(new MockMultipartFile(
        "file", "a.webp", "image/webp", WEBP_BYTES))).endsWith(".webp");
  }

  @Test
  void spoofedBytesForDeclaredTypeAreRejectedAndLeaveNoFile() throws IOException {
    // Non-image payload labelled as an image.
    assertSpoofRejected("image/png", "hello world!".getBytes());
    // Honest bytes, wrong declared type, in every direction.
    assertSpoofRejected("image/jpeg", PNG_BYTES);
    assertSpoofRejected("image/png", JPEG_BYTES);
    assertSpoofRejected("image/gif", PNG_BYTES);
    assertSpoofRejected("image/webp", GIF89_BYTES);
    // RIFF container that is not WebP (e.g. a WAV header).
    assertSpoofRejected("image/webp",
        new byte[]{'R', 'I', 'F', 'F', 0x10, 0, 0, 0, 'W', 'A', 'V', 'E'});
    // A GIF version that never existed.
    assertSpoofRejected("image/gif", "GIF88a..".getBytes());

    try (var stream = Files.list(uploadDir)) {
      assertThat(stream).isEmpty();
    }
  }

  @Test
  void truncatedHeadersAreRejectedAndLeaveNoFile() throws IOException {
    // Only the first half of the PNG signature.
    assertSpoofRejected("image/png",
        new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
    assertSpoofRejected("image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8});
    // RIFF present, WEBP marker cut off.
    assertSpoofRejected("image/webp",
        new byte[]{'R', 'I', 'F', 'F', 0x10, 0, 0, 0, 'W', 'E'});

    try (var stream = Files.list(uploadDir)) {
      assertThat(stream).isEmpty();
    }
  }

  private void assertSpoofRejected(String contentType, byte[] bytes) {
    MockMultipartFile file = new MockMultipartFile("file", "x", contentType, bytes);
    assertThatThrownBy(() -> storage.store(file))
        .isInstanceOf(InvalidImageException.class)
        .hasMessageContaining("does not match declared type");
  }

  @Test
  void delete_removesPreviouslyStoredFile() throws IOException {
    MockMultipartFile file =
        new MockMultipartFile("file", "lamp.png", "image/png", PNG_BYTES);
    String url = storage.store(file);

    assertThat(storage.delete(url)).isTrue();
    assertThat(uploadDir.resolve(url.substring("/uploads/".length()))).doesNotExist();
  }

  @Test
  void delete_missingFile_isNoOpReturningFalse() throws IOException {
    assertThat(storage.delete("/uploads/00000000-0000-0000-0000-000000000000.png"))
        .isFalse();
  }

  @Test
  void delete_nullBlankOrForeignPrefix_areNoOps() throws IOException {
    assertThat(storage.delete(null)).isFalse();
    assertThat(storage.delete("   ")).isFalse();
    assertThat(storage.delete("/other/x.png")).isFalse();
    assertThat(storage.delete("uploads/x.png")).isFalse();
  }

  @Test
  void delete_refusesPathTraversalOutsideUploadDir() throws IOException {
    Path secret = tmpDir.resolve("secret.txt");
    Files.write(secret, "do not touch".getBytes());

    assertThat(storage.delete("/uploads/../secret.txt")).isFalse();
    assertThat(secret).exists().content().isEqualTo("do not touch");
  }

  @Test
  void delete_prefixAlone_neverDeletesTheDirectoryItself() throws IOException {
    assertThat(storage.delete("/uploads/")).isFalse();
    assertThat(uploadDir).isDirectory();
  }
}
