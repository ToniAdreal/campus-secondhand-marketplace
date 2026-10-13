package com.toni.marketplace.item;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * Upload-storage health indicator (backlog #154): reports the health of
 * the one runtime dependency unique to this app — the local directory
 * behind {@link ImageStorageService} ({@code app.uploads.dir}, served by
 * {@link com.toni.marketplace.common.UploadWebConfig}).
 *
 * <p>Until now readiness ({@code management.endpoint.health.group.readiness},
 * backlog #128) reported only the database and the stock indicators, so an
 * unwritable or full upload directory surfaced only as user-facing
 * 400/500s on photo upload. This indicator is a member of the readiness
 * group ONLY — never liveness: a full disk must stop traffic routing,
 * not restart-loop the container.
 *
 * <p>Semantics: UP when the configured upload root exists (or can be
 * created, mirroring {@link ImageStorageService}'s own create-on-start
 * behaviour) and is writable with at least
 * {@code app.uploads.health-min-free} (default 10 MB) of usable space on
 * its file store; DOWN with a {@code reason} detail otherwise. Details
 * carry the configured path, the free bytes and the floor — never file
 * names from inside the directory. The health endpoint's disclosure
 * rules are unchanged ({@code show-details} stays at its default
 * {@code never}), so over HTTP only the component's UP/DOWN status is
 * visible anonymously, exactly like {@code db}.
 */
@Component("uploadStorage")
public class UploadStorageHealthIndicator implements HealthIndicator {

  private final Path dir;
  private final long minFreeBytes;

  @Autowired
  public UploadStorageHealthIndicator(
      @Value("${app.uploads.dir}") String uploadDir,
      @Value("${app.uploads.health-min-free:10MB}") DataSize minFree) {
    this(Paths.get(uploadDir).toAbsolutePath().normalize(), minFree.toBytes());
  }

  /** Test seam: an explicit root + floor, no Spring property plumbing. */
  UploadStorageHealthIndicator(Path dir, long minFreeBytes) {
    this.dir = dir.toAbsolutePath().normalize();
    this.minFreeBytes = minFreeBytes;
  }

  @Override
  public Health health() {
    try {
      if (Files.exists(dir) && !Files.isDirectory(dir)) {
        return down("upload path exists but is not a directory", -1);
      }
      if (!Files.exists(dir)) {
        // Creatable counts as healthy: ImageStorageService creates the
        // root on startup and re-ensures it on every store.
        Files.createDirectories(dir);
      }
      if (!Files.isWritable(dir)) {
        return down("upload directory is not writable", usableSpace());
      }
      long free = usableSpace();
      if (free >= 0 && free < minFreeBytes) {
        return down("free space below the configured floor", free);
      }
      return Health.up()
          .withDetail("path", dir.toString())
          .withDetail("freeBytes", free)
          .withDetail("minFreeBytes", minFreeBytes)
          .build();
    } catch (IOException | RuntimeException e) {
      return down("upload storage check failed: " + e.getClass().getSimpleName(), -1);
    }
  }

  private Health down(String reason, long freeBytes) {
    var builder = Health.down()
        .withDetail("path", dir.toString())
        .withDetail("reason", reason)
        .withDetail("minFreeBytes", minFreeBytes);
    if (freeBytes >= 0) {
      builder.withDetail("freeBytes", freeBytes);
    }
    return builder.build();
  }

  /** Usable bytes on the upload root's file store; -1 when unknown. */
  private long usableSpace() {
    try {
      FileStore store = Files.getFileStore(dir);
      return store.getUsableSpace();
    } catch (IOException | RuntimeException e) {
      return -1;
    }
  }
}
