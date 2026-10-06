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

  @Test
  void storesFileWithUuidNameAndContentTypeExtension() {
    byte[] bytes = {(byte) 0x89, 0x50, 0x4E, 0x47};
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
}
