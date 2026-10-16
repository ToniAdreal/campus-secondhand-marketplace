package com.toni.marketplace.item;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Flips past-deadline AVAILABLE listings to the stored EXPIRED status
 * (backlog #177). Expiry itself is lazy-on-read — the public browse
 * list excludes a past-deadline AVAILABLE listing and the detail read
 * answers 404 from the moment the deadline passes — so this nightly
 * job is bookkeeping, not a release valve: it gives the row a stored
 * terminal status for reporting and for the seller view's badge, using
 * the exact same {@link Item#isExpiredAt} definition as the reads, so
 * the job and the reads can never disagree on the same row (a test
 * pins that agreement).
 *
 * <p>Concurrency posture, mirroring {@code PendingOrderExpiryService}:
 * the job itself is NOT transactional — each listing flips in its own
 * transaction through the {@link ItemService} proxy, so a concurrent
 * renew winning on one row cannot roll back the batch. A row renewed
 * between the worklist scan and the row transaction is skipped by the
 * per-row re-check; a row transitioned mid-transaction loses its
 * {@code @Version} race and is likewise skipped — never a crash loop.
 *
 * <p>Honest scope: the schedule fires in the JVM's default time zone
 * (the cron is fixed, not zone-pinned). Because the reads are already
 * lazy, a stale row's stored status lags its effective state by at
 * most one nightly cadence — visible only in the seller view's stored
 * status, never in buyer-facing reads.
 */
@Service
public class ListingExpiryService {

  private static final Logger log = LoggerFactory.getLogger(ListingExpiryService.class);

  /** Listing ids re-read per repository call; keeps each select small. */
  static final int BATCH_SIZE = 500;

  private final ItemRepository items;
  private final ItemService itemService;
  private final Clock clock;

  public ListingExpiryService(ItemRepository items, ItemService itemService, Clock clock) {
    this.items = items;
    this.itemService = itemService;
    this.clock = clock;
  }

  /** How many stale listings one run flipped vs. skipped. */
  public record ExpiryCounts(int expired, int skipped) {}

  /** Runs nightly at 03:20 server-local; also callable directly in tests. */
  @Scheduled(cron = "0 20 3 * * *")
  public ExpiryCounts expireStaleListings() {
    Instant now = clock.instant();
    int expired = 0;
    int skipped = 0;
    List<Long> ids;
    do {
      // Always re-read page 0: rows change status between passes
      // (renewed, or flipped by this very loop), so a forward-moving
      // page index would skip rows.
      ids = items.findIdsByStatusAndExpiresAtBefore(ItemStatus.AVAILABLE, now,
          PageRequest.of(0, BATCH_SIZE));
      for (Long id : ids) {
        try {
          if (itemService.expireListing(id, now)) {
            expired++;
          } else {
            skipped++;
          }
        } catch (ObjectOptimisticLockingFailureException e) {
          // A concurrent transition of this row committed after the
          // row transaction's read: its transition won, the listing is
          // no longer ours to expire. Skipped, not failed.
          skipped++;
          log.info("listing expiry skipped item {}: a concurrent transition won", id);
        } catch (RuntimeException e) {
          // Defensive: one bad row must not stop the batch (the next
          // run retries it). Logged with the id — never swallowed.
          skipped++;
          log.warn("listing expiry failed for item {}", id, e);
        }
      }
    } while (ids.size() == BATCH_SIZE);
    log.info("listing expiry flipped {} listings to EXPIRED, skipped {}", expired, skipped);
    return new ExpiryCounts(expired, skipped);
  }
}
