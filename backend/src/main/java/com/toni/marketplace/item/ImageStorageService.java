package com.toni.marketplace.item;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;
import com.toni.marketplace.common.InvalidImageException;

/**
 * Stores listing photos on local disk and returns their public URL path.
 *
 * <ul>
 *   <li><b>Filename:</b> a fresh {@link UUID} plus an extension derived from
 *       the <i>declared</i> content type — the original client filename is
 *       never trusted and never reaches the served directory, so it cannot
 *       carry path traversal ({@code ../../}) or executable extensions
 *       ({@code .jsp}, {@code .html}) into it. This is a portfolio-scale
 *       tradeoff, not production hardening: AV scanning and object
 *       storage (S3/OSS) would be next.</li>
 *   <li><b>Validation:</b> allowlisted image content types only
 *       (png/jpeg/webp/gif), a configurable size cap
 *       ({@code app.uploads.max-size}, default 5 MB), and a magic-byte
 *       check: the leading bytes must match the declared type (PNG
 *       8-byte signature, JPEG {@code FFD8FF}, GIF87a/GIF89a, WebP
 *       {@code RIFF....WEBP}) before anything reaches disk, so a
 *       non-image payload labelled {@code image/png} is rejected with
 *       the same 400 envelope as other bad uploads and leaves no file
 *       behind. The check inspects only the signature, not the full
 *       image structure — a payload with an honest header and garbage
 *       after it still passes (full decoding would be the next step).
 *       Spring's servlet multipart limits act as a backstop and surface
 *       as 413.</li>
 *   <li><b>Metadata stripping (backlog #185):</b> JPEG and PNG uploads
 *       are parsed segment/chunk-wise in memory and their metadata is
 *       stripped BEFORE any file is created, pure JDK, no new
 *       dependency. JPEG: every APPn segment is dropped except APP0
 *       (the JFIF header — structural, no personal metadata) and an
 *       APP2 segment whose payload starts with {@code ICC_PROFILE\0}
 *       (the colour profile — kept so colours render as shot; other
 *       APP2 payloads are dropped), and COM comment segments are
 *       dropped; SOI, quantisation/Huffman/frame/scan segments and the
 *       entropy-coded scan data itself are copied byte-identically
 *       (the scan is never parsed, so marker-like bytes inside it
 *       survive). PNG: the ancillary metadata chunks {@code eXIf},
 *       {@code tEXt}, {@code iTXt} and {@code zTXt} are dropped; every
 *       other chunk (critical chunks and the remaining ancillary
 *       chunks) is copied byte-identically, CRCs included — each
 *       chunk's CRC32 is verified while parsing. GIF and WebP keep
 *       the signature-only behaviour above for now: their bytes are
 *       stored verbatim, metadata and all — stated honestly rather
 *       than claiming all formats are stripped. A malformed segment
 *       or chunk layout (truncated length, bad CRC, missing
 *       terminator) fails closed with the same
 *       {@link InvalidImageException} 400 as the magic gate and leaves
 *       no file behind — stripping runs fully in memory before the
 *       target file is opened.</li>
 *   <li><b>Serving:</b> files are served statically under
 *       {@code /uploads/**} by {@link
 *       com.toni.marketplace.common.UploadWebConfig}; the returned path is
 *       the public URL stored on {@link Item#setPhotoUrl}.</li>
 * </ul>
 */
@Service
public class ImageStorageService {

  /** Declared content type → safe extension. Nothing else may be uploaded. */
  private static final Map<String, String> ALLOWED_TYPES = Map.of(
      "image/png", ".png",
      "image/jpeg", ".jpg",
      "image/webp", ".webp",
      "image/gif", ".gif");

  /** Public URL prefix of every stored upload; the DB's photoUrl values. */
  static final String PUBLIC_PREFIX = "/uploads/";

  private final Path dir;
  private final DataSize maxSize;

  public ImageStorageService(
      @Value("${app.uploads.dir}") String uploadDir,
      @Value("${app.uploads.max-size}") DataSize maxSize) throws IOException {
    this.dir = Paths.get(uploadDir).toAbsolutePath().normalize();
    this.maxSize = maxSize;
    Files.createDirectories(this.dir);
  }

  /**
   * Validates and stores {@code file}, returning its public URL path
   * ({@code /uploads/<uuid>.<ext>}).
   *
   * @throws InvalidImageException if the file is empty, not an allowlisted
   *         image type, or over the size limit.
   */
  public String store(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new InvalidImageException("image file is required");
    }
    String ext = ALLOWED_TYPES.get(file.getContentType());
    if (ext == null) {
      throw new InvalidImageException("unsupported image type: " + file.getContentType());
    }
    if (file.getSize() > maxSize.toBytes()) {
      throw new InvalidImageException(
          "image too large (max " + maxSize.toMegabytes() + " MB)");
    }
    String filename = UUID.randomUUID() + ext;
    Path target = dir.resolve(filename).normalize();
    // Belt-and-braces: the filename is UUID-generated, but never write
    // outside the upload directory regardless.
    if (!target.startsWith(dir)) {
      throw new InvalidImageException("invalid file name");
    }
    final byte[] raw;
    try (InputStream in = file.getInputStream()) {
      raw = in.readAllBytes();
    } catch (IOException e) {
      throw new InvalidImageException("could not store image");
    }
    // Magic-byte gate BEFORE anything else: verify the signature header
    // against the declared type, so a spoofed or truncated upload never
    // leaves a file behind.
    byte[] header = java.util.Arrays.copyOf(raw, Math.min(raw.length, HEADER_BYTES));
    if (!matchesDeclaredType(file.getContentType(), header)) {
      throw new InvalidImageException(
          "image content does not match declared type: " + file.getContentType());
    }
    // Metadata stripping runs fully in memory (#185): a malformed
    // segment/chunk layout throws here, before the file is created.
    byte[] stored = stripMetadata(file.getContentType(), raw);
    try {
      // The directory is created in the constructor, but re-ensure it here:
      // an operator may have wiped it while the app was running.
      Files.createDirectories(dir);
      Files.write(target, stored);
    } catch (IOException e) {
      throw new InvalidImageException("could not store image");
    }
    return PUBLIC_PREFIX + filename;
  }

  /** Longest signature inspected (WebP needs bytes 0–11). */
  private static final int HEADER_BYTES = 12;

  private static final byte[] PNG_SIGNATURE = {
      (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

  /**
   * Pure byte checks against the declared type's magic bytes. A header
   * shorter than the signature (truncated upload) never matches.
   */
  static boolean matchesDeclaredType(String contentType, byte[] header) {
    if (contentType == null || header == null) {
      return false;
    }
    return switch (contentType) {
      case "image/png" -> startsWith(header, PNG_SIGNATURE);
      case "image/jpeg" -> header.length >= 3
          && header[0] == (byte) 0xFF && header[1] == (byte) 0xD8 && header[2] == (byte) 0xFF;
      case "image/gif" -> header.length >= 6
          && header[0] == 'G' && header[1] == 'I' && header[2] == 'F'
          && header[3] == '8' && (header[4] == '7' || header[4] == '9') && header[5] == 'a';
      case "image/webp" -> header.length >= 12
          && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
          && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
      default -> false;
    };
  }

  // ------------------------------------------------------------------
  // Metadata stripping (#185). Pure byte-structure work, no decoding.
  // ------------------------------------------------------------------

  private static final java.util.Set<String> PNG_METADATA_CHUNKS =
      java.util.Set.of("eXIf", "tEXt", "iTXt", "zTXt");

  /** ICC profile identifier at the start of a kept JPEG APP2 payload. */
  private static final byte[] ICC_PROFILE_ID = {
      'I', 'C', 'C', '_', 'P', 'R', 'O', 'F', 'I', 'L', 'E', 0};

  static byte[] stripMetadata(String contentType, byte[] raw) {
    return switch (contentType) {
      case "image/jpeg" -> stripJpegMetadata(raw);
      case "image/png" -> stripPngMetadata(raw);
      // GIF/WebP: stored verbatim for now (see class javadoc).
      default -> raw;
    };
  }

  private static InvalidImageException malformed(String detail) {
    return new InvalidImageException("malformed image content: " + detail);
  }

  /**
   * Walks JPEG segments from SOI to SOS/EOI. Kept segments are copied
   * byte-identically; the entropy-coded data after SOS is copied as one
   * opaque tail, so marker-like bytes inside the scan are never parsed.
   */
  static byte[] stripJpegMetadata(byte[] raw) {
    var out = new java.io.ByteArrayOutputStream(raw.length);
    out.write(raw, 0, 2); // SOI (magic gate already proved FF D8 FF)
    int pos = 2;
    while (true) {
      if (pos + 1 >= raw.length) {
        throw malformed("jpeg ends before EOI/SOS");
      }
      if (raw[pos] != (byte) 0xFF) {
        throw malformed("jpeg segment does not start with 0xFF");
      }
      int segmentStart = pos;
      // Skip padding 0xFF fill bytes before the marker code.
      int markerPos = pos + 1;
      while (markerPos < raw.length && raw[markerPos] == (byte) 0xFF) {
        markerPos++;
      }
      if (markerPos >= raw.length) {
        throw malformed("jpeg truncated marker");
      }
      int marker = raw[markerPos] & 0xFF;
      pos = markerPos + 1;
      if (marker == 0x00 || marker == 0xD8) {
        throw malformed("jpeg unexpected marker 0x" + Integer.toHexString(marker));
      }
      if (marker == 0xD9) { // EOI: copy it and finish.
        out.write(raw, segmentStart, pos - segmentStart);
        return out.toByteArray();
      }
      if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
        // Standalone markers carry no length field.
        out.write(raw, segmentStart, pos - segmentStart);
        continue;
      }
      if (pos + 1 >= raw.length) {
        throw malformed("jpeg truncated segment length");
      }
      int length = ((raw[pos] & 0xFF) << 8) | (raw[pos + 1] & 0xFF);
      if (length < 2) {
        throw malformed("jpeg segment length < 2");
      }
      int segmentEnd = pos + length; // length counts its own 2 bytes
      if (segmentEnd > raw.length) {
        throw malformed("jpeg segment extends past end of file");
      }
      if (marker == 0xDA) { // SOS: header + the whole scan, verbatim.
        out.write(raw, segmentStart, raw.length - segmentStart);
        return out.toByteArray();
      }
      if (!isStrippedJpegSegment(marker, raw, pos + 2, segmentEnd)) {
        out.write(raw, segmentStart, segmentEnd - segmentStart);
      }
      pos = segmentEnd;
    }
  }

  private static boolean isStrippedJpegSegment(
      int marker, byte[] raw, int dataStart, int segmentEnd) {
    if (marker == 0xFE) { // COM comment
      return true;
    }
    if (marker < 0xE0 || marker > 0xEF) { // not APPn
      return false;
    }
    if (marker == 0xE0) { // APP0 JFIF header: structural, kept.
      return false;
    }
    if (marker == 0xE2 && segmentEnd - dataStart >= ICC_PROFILE_ID.length
        && startsWith(
            java.util.Arrays.copyOfRange(raw, dataStart, segmentEnd), ICC_PROFILE_ID)) {
      return false; // APP2 carrying the ICC colour profile: kept.
    }
    return true; // APP1 (EXIF/XMP), other APPn, non-ICC APP2
  }

  /**
   * Walks PNG chunks from the signature to IEND, verifying each chunk's
   * CRC32 (over type + data) and dropping the metadata chunk types.
   * Kept chunks are copied byte-identically, CRCs included.
   */
  static byte[] stripPngMetadata(byte[] raw) {
    var out = new java.io.ByteArrayOutputStream(raw.length);
    out.write(raw, 0, 8); // signature (magic gate already proved it)
    int pos = 8;
    boolean firstChunk = true;
    while (true) {
      if (pos + 8 > raw.length) {
        throw malformed("png truncated chunk header");
      }
      long lengthLong =
          ((long) (raw[pos] & 0xFF) << 24) | ((raw[pos + 1] & 0xFF) << 16)
              | ((raw[pos + 2] & 0xFF) << 8) | (raw[pos + 3] & 0xFF);
      if (lengthLong > Integer.MAX_VALUE) {
        throw malformed("png chunk too large");
      }
      int length = (int) lengthLong;
      String type = new String(raw, pos + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
      for (int i = 0; i < 4; i++) {
        byte b = raw[pos + 4 + i];
        if (!((b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z'))) {
          throw malformed("png chunk type is not ASCII letters");
        }
      }
      if (firstChunk && !"IHDR".equals(type)) {
        throw malformed("png first chunk is not IHDR");
      }
      firstChunk = false;
      long chunkEndLong = (long) pos + 8 + length + 4;
      if (chunkEndLong > raw.length) {
        throw malformed("png chunk extends past end of file");
      }
      int chunkEnd = (int) chunkEndLong;
      var crc = new java.util.zip.CRC32();
      crc.update(raw, pos + 4, 4 + length);
      long storedCrc =
          ((long) (raw[chunkEnd - 4] & 0xFF) << 24) | ((raw[chunkEnd - 3] & 0xFF) << 16)
              | ((raw[chunkEnd - 2] & 0xFF) << 8) | (raw[chunkEnd - 1] & 0xFF);
      if (crc.getValue() != storedCrc) {
        throw malformed("png chunk CRC mismatch");
      }
      if (!PNG_METADATA_CHUNKS.contains(type)) {
        out.write(raw, pos, chunkEnd - pos);
      }
      pos = chunkEnd;
      if ("IEND".equals(type)) {
        if (pos != raw.length) {
          throw malformed("png trailing bytes after IEND");
        }
        return out.toByteArray();
      }
    }
  }

  private static boolean startsWith(byte[] header, byte[] signature) {
    if (header.length < signature.length) {
      return false;
    }
    for (int i = 0; i < signature.length; i++) {
      if (header[i] != signature[i]) {
        return false;
      }
    }
    return true;
  }

  /**
   * Best-effort delete of a previously stored upload, identified by its
   * public URL path ({@code /uploads/<uuid>.<ext>}).
   *
   * <p>Only paths inside the upload directory are ever touched: anything
   * else (null/blank, a foreign prefix, or a {@code ..} traversal that
   * resolves outside the directory) is a no-op returning {@code false}, and
   * only regular files are deleted — never directories.
   *
   * @return {@code true} if a file was actually removed.
   * @throws IOException on an unexpected filesystem failure. Callers that
   *         delete alongside a database row must treat this as best-effort
   *         (log and continue) so a disk hiccup can never roll back the row
   *         operation — filesystem deletes are not transactional.
   */
  public boolean delete(String publicPath) throws IOException {
    if (publicPath == null || publicPath.isBlank() || !publicPath.startsWith(PUBLIC_PREFIX)) {
      return false;
    }
    Path target = dir.resolve(publicPath.substring(PUBLIC_PREFIX.length())).normalize();
    if (!target.startsWith(dir) || !Files.isRegularFile(target)) {
      return false;
    }
    return Files.deleteIfExists(target);
  }
}
