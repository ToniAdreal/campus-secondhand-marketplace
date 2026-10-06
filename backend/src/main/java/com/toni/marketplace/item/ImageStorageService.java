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

/**
 * Stores listing photos on local disk and returns their public URL path.
 *
 * <ul>
 *   <li><b>Filename:</b> a fresh {@link UUID} plus an extension derived from
 *       the <i>declared</i> content type — the original client filename is
 *       never trusted and never reaches the served directory, so it cannot
 *       carry path traversal ({@code ../../}) or executable extensions
 *       ({@code .jsp}, {@code .html}) into it. This is a portfolio-scale
 *       tradeoff, not production hardening: content-type sniffing (magic
 *       bytes), AV scanning, and object storage (S3/OSS) would be next.</li>
 *   <li><b>Validation:</b> allowlisted image content types only
 *       (png/jpeg/webp/gif) and a configurable size cap
 *       ({@code app.uploads.max-size}, default 5 MB). Spring's servlet
 *       multipart limits act as a backstop and surface as 413.</li>
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
        Files.copy(in, target);
      }
    } catch (IOException e) {
      throw new InvalidImageException("could not store image");
    }
    return "/uploads/" + filename;
  }
}
