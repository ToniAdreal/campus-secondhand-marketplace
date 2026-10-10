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
    try {
      // The directory is created in the constructor, but re-ensure it here:
      // an operator may have wiped it while the app was running.
      Files.createDirectories(dir);
      try (InputStream in = file.getInputStream()) {
        // Magic-byte gate BEFORE the file is created: read the signature
        // header first and verify it against the declared type, so a
        // spoofed or truncated upload never leaves a file behind.
        byte[] header = in.readNBytes(HEADER_BYTES);
        if (!matchesDeclaredType(file.getContentType(), header)) {
          throw new InvalidImageException(
              "image content does not match declared type: " + file.getContentType());
        }
        try (var out = Files.newOutputStream(target)) {
          out.write(header);
          in.transferTo(out);
        }
      }
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
