package com.toni.marketplace.job;

import com.toni.marketplace.item.ItemPhotoRepository;
import com.toni.marketplace.item.ItemRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Nightly sweeper for orphaned upload files (backlog #103).
 *
 * <p>Listing photos are written to disk <em>before</em> the surrounding
 * transaction commits (see {@code ItemService.attachPhoto} /
 * {@code addPhoto}), so a rollback after a successful write leaves a file
 * under {@code app.uploads.dir} that no database row references. The
 * delete/replace paths clean up after themselves (backlog #40), but
 * rollback orphans have no owner left to clean them — this job is that
 * owner.
 *
 * <p>A file is swept only when <b>both</b> hold:
 *
 * <ul>
 *   <li>its public name ({@code /uploads/<name>}) is referenced by neither
 *   {@code item.photo_url} nor any {@code item_photo} row; and</li>
 *   <li>its last-modified time is older than the grace period
 *   ({@code app.uploads.orphan-grace}, default 24 h). The grace period is
 *   what makes the sweep safe against the write-before-commit window: a
 *   file whose transaction is still in flight — written, not yet
 *   referenced by any committed row — is always far younger than the
 *   grace period and is never swept.</li>
 * </ul>
 *
 * <p>Best-effort per file: one file that cannot be inspected or deleted is
 * logged and skipped, never aborting the rest of the sweep, and a missing
 * upload directory is a no-op (a fresh deployment has no uploads yet),
 * not an error. Only regular files directly inside the upload directory
 * are considered — uploads are flat UUID names, and the sweeper never
 * recurses into or removes directories.
 *
 * <p>Honest scope: the reference set is read once per run, so a file
 * written and committed between the read and the delete could in theory
 * be swept while referenced — the 24 h grace period makes that window
 * unreachable in practice (the file would have to be a day old already),
 * which is exactly the trade-off the grace period buys.
 */
@Service
public class OrphanUploadSweeper {

  private static final Logger log = LoggerFactory.getLogger(OrphanUploadSweeper.class);

  /** Public URL prefix of every stored upload; the DB's photo values. */
  private static final String PUBLIC_PREFIX = "/uploads/";

  private final ItemRepository items;
  private final ItemPhotoRepository itemPhotos;
  private final Path dir;
  private final Duration grace;
  private final Clock clock;

  public OrphanUploadSweeper(ItemRepository items,
                             ItemPhotoRepository itemPhotos,
                             @Value("${app.uploads.dir}") String uploadDir,
                             @Value("${app.uploads.orphan-grace}") Duration grace,
                             Clock clock) {
    this.items = items;
    this.itemPhotos = itemPhotos;
    this.dir = Paths.get(uploadDir).toAbsolutePath().normalize();
    this.grace = grace;
    this.clock = clock;
  }

  /**
   * Runs nightly at 03:30 server-local time (after the 03:00 stale-row
   * purge). Also callable directly in tests with a controllable
   * {@link Clock}.
   *
   * @return how many orphan files were deleted.
   */
  @Scheduled(cron = "0 30 3 * * *")
  public int sweepOrphanUploads() {
    if (!Files.isDirectory(dir)) {
      log.debug("upload directory {} does not exist; orphan sweep is a no-op", dir);
      return 0;
    }
    Set<String> referenced = referencedFileNames();
    Instant cutoff = clock.instant().minus(grace);
    int deleted = 0;
    try (Stream<Path> entries = Files.list(dir)) {
      for (Path file : entries.toList()) {
        if (sweepOne(file, referenced, cutoff)) {
          deleted++;
        }
      }
    } catch (IOException e) {
      // The directory existed a moment ago; a listing failure is logged and
      // the sweep retries tomorrow night rather than failing the scheduler.
      log.warn("orphan upload sweep could not list {}: {}", dir, e.toString());
      return deleted;
    }
    if (deleted > 0) {
      log.info("orphan upload sweep removed {} unreferenced file(s) from {}", deleted, dir);
    }
    return deleted;
  }

  /** One file's verdict: {@code true} only if it was actually deleted. */
  private boolean sweepOne(Path file, Set<String> referenced, Instant cutoff) {
    try {
      if (!Files.isRegularFile(file) || referenced.contains(file.getFileName().toString())) {
        return false;
      }
      // A just-written pre-commit file must never be swept: only files
      // whose mtime predates the grace cut-off are orphans.
      if (Files.getLastModifiedTime(file).toInstant().isAfter(cutoff)) {
        return false;
      }
      return Files.deleteIfExists(file);
    } catch (IOException e) {
      log.warn("orphan upload sweep skipped {}: {}", file, e.toString());
      return false;
    }
  }

  /**
   * File names referenced by any live row: every listing's primary
   * {@code photo_url} plus every gallery ({@code item_photo}) URL. Values
   * that are not local {@code /uploads/} paths cannot name a file in the
   * upload directory and are ignored.
   */
  private Set<String> referencedFileNames() {
    Set<String> names = new HashSet<>();
    for (String url : items.findAllPhotoUrls()) {
      addFileName(names, url);
    }
    for (String url : itemPhotos.findAllUrls()) {
      addFileName(names, url);
    }
    return names;
  }

  private static void addFileName(Set<String> names, String url) {
    if (url == null || !url.startsWith(PUBLIC_PREFIX)) {
      return;
    }
    String name = url.substring(PUBLIC_PREFIX.length());
    // Stored names are flat UUID file names; anything carrying a path
    // separator does not name a file directly inside the upload directory.
    if (!name.isBlank() && !name.contains("/") && !name.contains("\\")) {
      names.add(name);
    }
  }
}
