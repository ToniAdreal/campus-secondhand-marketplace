package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/**
 * Unit tests for {@link UploadStorageHealthIndicator} (backlog #154) over
 * {@code @TempDir} roots — no Spring context: the UP/DOWN contract, the
 * free-space floor and the details discipline (path + free bytes, never
 * file names) are exercised directly.
 */
class UploadStorageHealthIndicatorTest {

  @TempDir
  private Path tmpDir;

  @Test
  void writableDirectoryIsUpWithPathAndFreeBytes() {
    Health health = new UploadStorageHealthIndicator(tmpDir, 1).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("path", tmpDir.toAbsolutePath().normalize().toString())
        .containsEntry("minFreeBytes", 1L);
    assertThat((Long) health.getDetails().get("freeBytes")).isPositive();
  }

  @Test
  void missingDirectoryThatCanBeCreatedIsUp() throws IOException {
    Path missing = tmpDir.resolve("uploads");
    assertThat(missing).doesNotExist();

    Health health = new UploadStorageHealthIndicator(missing, 1).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(missing).isDirectory();
  }

  @Test
  void regularFileInPlaceOfDirectoryIsDown() throws IOException {
    Path file = tmpDir.resolve("not-a-dir");
    Files.writeString(file, "occupied");

    Health health = new UploadStorageHealthIndicator(file, 1).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("reason", "upload path exists but is not a directory");
  }

  @Test
  void freeSpaceBelowTheFloorIsDown() {
    // A floor no real file store can satisfy pins the breach branch
    // without depending on the host disk's actual free space.
    Health health =
        new UploadStorageHealthIndicator(tmpDir, Long.MAX_VALUE).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("reason", "free space below the configured floor")
        .containsEntry("minFreeBytes", Long.MAX_VALUE);
    assertThat((Long) health.getDetails().get("freeBytes")).isPositive();
  }

  @Test
  void detailsNeverCarryFileNames() throws IOException {
    Files.writeString(tmpDir.resolve("secret-listing-photo.png"), "x");

    Health health = new UploadStorageHealthIndicator(tmpDir, 1).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails().toString())
        .doesNotContain("secret-listing-photo");
  }
}
