package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toni.marketplace.common.InvalidImageException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

/**
 * Backlog #185: metadata stripping in {@link ImageStorageService} —
 * JPEG APPn/COM and PNG text/EXIF chunks are removed before the file is
 * created, image segments/chunks stay byte-identical, GIF/WebP pass
 * through verbatim, and a malformed layout fails closed leaving no file.
 */
class ImageMetadataStripTest {

  @TempDir
  private Path tmpDir;

  private Path uploadDir;
  private ImageStorageService storage;

  @BeforeEach
  void setUp() throws IOException {
    uploadDir = tmpDir.resolve("uploads");
    storage = new ImageStorageService(uploadDir.toString(), DataSize.ofMegabytes(5));
  }

  // ---------------- fixtures ----------------

  private static byte[] segment(int marker, byte[] data) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(0xFF);
    out.write(marker);
    int length = data.length + 2;
    out.write((length >> 8) & 0xFF);
    out.write(length & 0xFF);
    out.write(data, 0, data.length);
    return out.toByteArray();
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.write(part, 0, part.length);
    }
    return out.toByteArray();
  }

  private static final byte[] SOI = {(byte) 0xFF, (byte) 0xD8};
  private static final byte[] EOI = {(byte) 0xFF, (byte) 0xD9};
  private static final byte[] JFIF = segment(0xE0,
      concat("JFIF\0".getBytes(StandardCharsets.US_ASCII),
          new byte[] {1, 1, 0, 0, 1, 0, 1, 0, 0}));
  private static final byte[] EXIF_APP1 = segment(0xE1,
      concat("Exif\0\0".getBytes(StandardCharsets.US_ASCII),
          // Fake GPS-flavoured payload — the bytes that must not survive.
          new byte[] {0x49, 0x49, 0x2A, 0x00, 'G', 'P', 'S', ':', '3', '0', '.', '2', '7'}));
  private static final byte[] ICC_APP2 = segment(0xE2,
      concat("ICC_PROFILE\0".getBytes(StandardCharsets.US_ASCII),
          new byte[] {1, 1, 'p', 'r', 'o', 'f', 'i', 'l', 'e'}));
  private static final byte[] XMP_APP2 = segment(0xE2,
      "not-a-profile".getBytes(StandardCharsets.US_ASCII));
  private static final byte[] COMMENT = segment(0xFE,
      "shot in my dorm".getBytes(StandardCharsets.US_ASCII));
  private static final byte[] SOF0 = segment(0xC0,
      new byte[] {8, 0, 1, 0, 1, 1, 1, 0x11, 0});
  private static final byte[] SOS_HEADER = segment(0xDA,
      new byte[] {1, 1, 0, 0, 63, 0});
  /** Scan data: stuffed FF00, a fake APP1 lookalike and the real EOI. */
  private static final byte[] SCAN = concat(
      new byte[] {0x11, 0x22, (byte) 0xFF, 0x00, 0x33},
      EXIF_APP1,
      new byte[] {0x44},
      EOI);

  /** Complete minimal PNG (1x1), the ItemPhotoUploadTest fixture. */
  private static final byte[] CLEAN_PNG = {
      (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
      0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
      0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
      0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
      (byte) 0x89, 0x00, 0x00, 0x00, 0x0B, 0x49, 0x44, 0x41,
      0x54, 0x78, (byte) 0x9C, 0x63, 0x60, 0x00, 0x02, 0x00,
      0x00, 0x05, 0x00, 0x01, 0x7A, 0x5E, (byte) 0xAB, 0x3F,
      0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
      (byte) 0xAE, 0x42, 0x60, (byte) 0x82};

  private static byte[] pngChunk(String type, byte[] data) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write((data.length >> 24) & 0xFF);
    out.write((data.length >> 16) & 0xFF);
    out.write((data.length >> 8) & 0xFF);
    out.write(data.length & 0xFF);
    byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
    out.write(typeBytes, 0, 4);
    out.write(data, 0, data.length);
    CRC32 crc = new CRC32();
    crc.update(typeBytes, 0, 4);
    crc.update(data, 0, data.length);
    long value = crc.getValue();
    out.write((int) (value >> 24) & 0xFF);
    out.write((int) (value >> 16) & 0xFF);
    out.write((int) (value >> 8) & 0xFF);
    out.write((int) value & 0xFF);
    return out.toByteArray();
  }

  /** The clean PNG with metadata chunks inserted right after IHDR. */
  private static byte[] dirtyPng() {
    int ihdrEnd = 8 + 25; // signature + IHDR chunk (4+4+13+4)
    return concat(
        java.util.Arrays.copyOfRange(CLEAN_PNG, 0, ihdrEnd),
        pngChunk("tEXt", "Location\0Ningbo dorm".getBytes(StandardCharsets.US_ASCII)),
        pngChunk("eXIf", new byte[] {0x49, 0x49, 0x2A, 0x00, 'G', 'P', 'S'}),
        pngChunk("zTXt", new byte[] {'k', 0, 0, 1, 2, 3}),
        pngChunk("iTXt", new byte[] {'k', 0, 0, 0, 0, 'v'}),
        java.util.Arrays.copyOfRange(CLEAN_PNG, ihdrEnd, CLEAN_PNG.length));
  }

  private byte[] storedBytes(String url) throws IOException {
    return Files.readAllBytes(uploadDir.resolve(url.substring("/uploads/".length())));
  }

  // ---------------- JPEG ----------------

  @Test
  void jpegExifAndOtherMetadataStrippedImageBytesIntact() throws IOException {
    byte[] dirty = concat(SOI, JFIF, EXIF_APP1, ICC_APP2, XMP_APP2, COMMENT, SOF0,
        SOS_HEADER, SCAN);
    byte[] expected = concat(SOI, JFIF, ICC_APP2, SOF0, SOS_HEADER, SCAN);

    String url = storage.store(new MockMultipartFile("file", "p.jpg", "image/jpeg", dirty));

    // The APP1-looking bytes inside the entropy scan survive untouched —
    // only real header segments are stripped.
    assertThat(storedBytes(url)).isEqualTo(expected);
    assertThat(new String(storedBytes(url), StandardCharsets.ISO_8859_1))
        .doesNotContain("shot in my dorm");
  }

  @Test
  void jpegWithoutMetadataIsByteIdentical() throws IOException {
    byte[] clean = concat(SOI, JFIF, SOF0, SOS_HEADER, SCAN);

    String url = storage.store(new MockMultipartFile("file", "p.jpg", "image/jpeg", clean));

    assertThat(storedBytes(url)).isEqualTo(clean);
  }

  @Test
  void jpegTruncatedSegmentIsRejectedAndLeavesNoFile() throws IOException {
    // APP1 declares 0x0100 bytes but the file ends almost immediately.
    byte[] truncated = concat(SOI, JFIF,
        new byte[] {(byte) 0xFF, (byte) 0xE1, 0x01, 0x00, 'E', 'x'});

    assertThatThrownBy(() -> storage.store(
        new MockMultipartFile("file", "p.jpg", "image/jpeg", truncated)))
        .isInstanceOf(InvalidImageException.class)
        .hasMessageContaining("malformed image content");
    try (var stream = Files.list(uploadDir)) {
      assertThat(stream).isEmpty();
    }
  }

  @Test
  void jpegSegmentLengthBelowTwoIsRejectedAndLeavesNoFile() throws IOException {
    byte[] bad = concat(SOI,
        new byte[] {(byte) 0xFF, (byte) 0xE1, 0x00, 0x01, 'x'}, EOI);

    assertThatThrownBy(() -> storage.store(
        new MockMultipartFile("file", "p.jpg", "image/jpeg", bad)))
        .isInstanceOf(InvalidImageException.class)
        .hasMessageContaining("malformed image content");
    try (var stream = Files.list(uploadDir)) {
      assertThat(stream).isEmpty();
    }
  }

  // ---------------- PNG ----------------

  @Test
  void pngMetadataChunksStrippedCriticalChunksIntact() throws IOException {
    String url = storage.store(
        new MockMultipartFile("file", "p.png", "image/png", dirtyPng()));

    // Exactly the clean PNG: IHDR/IDAT/IEND kept byte-identically.
    assertThat(storedBytes(url)).isEqualTo(CLEAN_PNG);
  }

  @Test
  void pngWithoutMetadataIsByteIdentical() throws IOException {
    String url = storage.store(
        new MockMultipartFile("file", "p.png", "image/png", CLEAN_PNG));

    assertThat(storedBytes(url)).isEqualTo(CLEAN_PNG);
  }

  @Test
  void pngTruncatedChunkIsRejectedAndLeavesNoFile() throws IOException {
    int ihdrEnd = 8 + 25;
    byte[] truncated = concat(
        java.util.Arrays.copyOfRange(CLEAN_PNG, 0, ihdrEnd),
        // tEXt header claiming 100 data bytes, only 3 present, no CRC.
        new byte[] {0, 0, 0, 100, 't', 'E', 'X', 't', 'a', 'b', 'c'});

    assertThatThrownBy(() -> storage.store(
        new MockMultipartFile("file", "p.png", "image/png", truncated)))
        .isInstanceOf(InvalidImageException.class)
        .hasMessageContaining("malformed image content");
    try (var stream = Files.list(uploadDir)) {
      assertThat(stream).isEmpty();
    }
  }

  @Test
  void pngBadCrcIsRejectedAndLeavesNoFile() throws IOException {
    byte[] chunk = pngChunk("tEXt", "k\0v".getBytes(StandardCharsets.US_ASCII));
    chunk[chunk.length - 1] ^= 0xFF; // corrupt the CRC
    int ihdrEnd = 8 + 25;
    byte[] bad = concat(
        java.util.Arrays.copyOfRange(CLEAN_PNG, 0, ihdrEnd),
        chunk,
        java.util.Arrays.copyOfRange(CLEAN_PNG, ihdrEnd, CLEAN_PNG.length));

    assertThatThrownBy(() -> storage.store(
        new MockMultipartFile("file", "p.png", "image/png", bad)))
        .isInstanceOf(InvalidImageException.class)
        .hasMessageContaining("malformed image content");
    try (var stream = Files.list(uploadDir)) {
      assertThat(stream).isEmpty();
    }
  }

  // ---------------- GIF / WebP passthrough ----------------

  @Test
  void gifAndWebpAreStoredVerbatimForNow() throws IOException {
    byte[] gif = "GIF89a-metadata-and-all".getBytes(StandardCharsets.US_ASCII);
    String gifUrl = storage.store(new MockMultipartFile("file", "a.gif", "image/gif", gif));
    assertThat(storedBytes(gifUrl)).isEqualTo(gif);

    byte[] webp = {'R', 'I', 'F', 'F', 0x14, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', 'X'};
    String webpUrl = storage.store(
        new MockMultipartFile("file", "a.webp", "image/webp", webp));
    assertThat(storedBytes(webpUrl)).isEqualTo(webp);
  }
}
