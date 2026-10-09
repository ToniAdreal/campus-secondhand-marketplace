package com.toni.marketplace.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.toni.marketplace.auth.MutableClock;
import com.toni.marketplace.item.ItemPhotoRepository;
import com.toni.marketplace.item.ItemRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the {@link OrphanUploadSweeper} against a real temp
 * directory, with the two reference-set queries mocked and a
 * {@link MutableClock} fixing "now". File mtimes are set explicitly, so
 * the grace-period arithmetic — not wall time — decides every verdict:
 * referenced files survive at any age, unreferenced files survive only
 * inside the grace window.
 */
@ExtendWith(MockitoExtension.class)
class OrphanUploadSweeperTest {

  private static final Duration GRACE = Duration.ofHours(24);

  @Mock
  private ItemRepository items;

  @Mock
  private ItemPhotoRepository itemPhotos;

  @TempDir
  private Path uploadDir;

  private MutableClock clock;
  private OrphanUploadSweeper sweeper;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(Instant.parse("2026-10-09T00:00:00Z"));
    sweeper = new OrphanUploadSweeper(items, itemPhotos,
        uploadDir.toString(), GRACE, clock);
  }

  /** Writes a file whose mtime is {@code age} before the clock's now. */
  private Path writeFile(String name, Duration age) throws IOException {
    Path file = Files.write(uploadDir.resolve(name), new byte[] {1});
    Files.setLastModifiedTime(file,
        FileTime.from(clock.instant().minus(age)));
    return file;
  }

  @Test
  void referencedPrimaryAndGalleryFilesAreKept() throws IOException {
    Path primary = writeFile("primary.png", Duration.ofDays(30));
    Path gallery = writeFile("gallery.jpg", Duration.ofDays(30));
    when(items.findAllPhotoUrls()).thenReturn(List.of("/uploads/primary.png"));
    when(itemPhotos.findAllUrls()).thenReturn(List.of("/uploads/gallery.jpg"));

    int deleted = sweeper.sweepOrphanUploads();

    assertThat(deleted).isZero();
    assertThat(primary).exists();
    assertThat(gallery).exists();
  }

  @Test
  void unreferencedOldFileIsDeleted() throws IOException {
    Path orphan = writeFile("orphan.webp", GRACE.plusMinutes(1));
    when(items.findAllPhotoUrls()).thenReturn(List.of());
    when(itemPhotos.findAllUrls()).thenReturn(List.of());

    int deleted = sweeper.sweepOrphanUploads();

    assertThat(deleted).isEqualTo(1);
    assertThat(orphan).doesNotExist();
  }

  @Test
  void unreferencedYoungFileIsKept() throws IOException {
    // A file written seconds ago may belong to a transaction that has not
    // committed yet — the grace window exists precisely for it.
    Path fresh = writeFile("fresh.gif", Duration.ofMinutes(5));
    when(items.findAllPhotoUrls()).thenReturn(List.of());
    when(itemPhotos.findAllUrls()).thenReturn(List.of());

    int deleted = sweeper.sweepOrphanUploads();

    assertThat(deleted).isZero();
    assertThat(fresh).exists();
  }

  @Test
  void missingUploadDirectoryIsANoOp() {
    OrphanUploadSweeper missing = new OrphanUploadSweeper(items, itemPhotos,
        uploadDir.resolve("no-such-dir").toString(), GRACE, clock);

    assertThat(missing.sweepOrphanUploads()).isZero();
  }

  @Test
  void mixedDirectorySweepsOnlyOldOrphans() throws IOException {
    Path referenced = writeFile("kept-ref.png", Duration.ofDays(7));
    Path young = writeFile("kept-young.png", Duration.ofHours(1));
    Path orphan = writeFile("gone.png", Duration.ofDays(7));
    Path subdir = Files.createDirectory(uploadDir.resolve("nested"));
    when(items.findAllPhotoUrls()).thenReturn(List.of("/uploads/kept-ref.png"));
    when(itemPhotos.findAllUrls()).thenReturn(List.of());

    int deleted = sweeper.sweepOrphanUploads();

    assertThat(deleted).isEqualTo(1);
    assertThat(referenced).exists();
    assertThat(young).exists();
    assertThat(orphan).doesNotExist();
    // Directories are never swept, even unreferenced and old.
    assertThat(subdir).isDirectory();
  }
}
