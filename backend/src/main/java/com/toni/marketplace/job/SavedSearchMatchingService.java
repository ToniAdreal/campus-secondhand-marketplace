package com.toni.marketplace.job;

import com.toni.marketplace.item.ItemDto;
import com.toni.marketplace.item.ItemService;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.item.SavedSearch;
import com.toni.marketplace.item.SavedSearchRepository;
import com.toni.marketplace.notification.NotificationService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Background matching for saved searches (backlog #182): the job the
 * #164 v1 deliberately did not have. It lives in {@code job/} — the
 * only cross-domain package (see {@code ArchitectureTest}) — because a
 * run legitimately touches the item domain (saved searches + the browse
 * projection) and the notification domain in one transaction.
 *
 * <p>Matching reuses the read path exactly: for each saved search the
 * matcher pages through {@link ItemService#listItems} with the search's
 * own stored filter set — the same filtered projection the browse page
 * and the read-side matchCount use (LIKE path on H2, full-text path on
 * MySQL), never a second query dialect. Over that projection the
 * matcher applies the "new listing" rule itself:
 *
 * <ul>
 *   <li><b>Watermark</b> — the search's {@code lastNotifiedItemId} is
 *       the highest listing id the matcher has SEEN for it (Flyway
 *       V45; the backlog's implementation note picks the highest-seen
 *       id over a timestamp — ids are monotonic, so there are no
 *       clock-skew or same-instant ties). Only a listing with a
 *       greater id is a candidate. A null watermark means the row
 *       predates the matcher: the run only baselines it (to the
 *       current highest listing id) and notifies nothing; searches
 *       created through the API are baselined at creation time, so
 *       this only ever applies to legacy rows.</li>
 *   <li><b>Status</b> — only AVAILABLE listings count as matches. The
 *       browse projection deliberately carries RESERVED/SOLD rows
 *       (the seller and detail views need them), so the matcher
 *       filters them out here and pins that in tests: a RESERVED or
 *       SOLD listing never alerts anyone. Such rows still advance
 *       the watermark as "seen" — a listing that later flips back to
 *       AVAILABLE is not a new listing and does not alert then
 *       either.</li>
 *   <li><b>Own listings</b> — a listing whose seller is the search's
 *       owner never matches that owner's own search; searches and
 *       notifications never cross users.</li>
 * </ul>
 *
 * <p>Batching: one run writes AT MOST one
 * {@code SAVED_SEARCH_MATCH} notification per saved search, carrying
 * the total new-match count — five new matches are one row saying 5,
 * never five rows. The notification is recorded BEFORE the watermark
 * advances, and the whole run is one transaction (the
 * {@code @Transactional} sits on the scheduled method itself, as on
 * {@link DataRetentionService#purgeStaleData}): a failed run rolls
 * back, advancing nothing and writing no partial notifications, and
 * the next run retries from the same watermarks. A repeat run over
 * unchanged listings is silent, because every candidate id is now at
 * or below the watermark. Deleting a saved search removes it from the
 * worklist, so it can never match again.
 *
 * <p>Honest scope: in-app notifications only — no email, no push.
 * The schedule fires in the JVM's default time zone (the cron is
 * fixed, not zone-pinned). Each firing first takes this job's
 * single-instance lease (backlog #215, {@link JobLockGuard}) — this
 * job is the one whose double-run is user-visible as duplicate
 * notifications; the lease is acquired in its own transaction before
 * the matching transaction's work and released only after that
 * transaction completes, so a second instance cannot start on
 * half-committed watermarks. A skipped firing returns zero counts.
 */
@Service
public class SavedSearchMatchingService {

  private static final Logger log = LoggerFactory.getLogger(SavedSearchMatchingService.class);

  /** Browse-projection page size while scanning one search's matches. */
  static final int PAGE_SIZE = 200;

  /** Single-instance lease name for this job (backlog #215). */
  public static final String LOCK_NAME = "saved-search-matching";

  private final SavedSearchRepository savedSearches;
  private final ItemService itemService;
  private final NotificationService notifications;
  private final JobLockGuard jobLock;

  @Autowired
  public SavedSearchMatchingService(SavedSearchRepository savedSearches,
                                    ItemService itemService,
                                    NotificationService notifications,
                                    JobLockGuard jobLock) {
    this.savedSearches = savedSearches;
    this.itemService = itemService;
    this.notifications = notifications;
    this.jobLock = jobLock;
  }

  /** Direct-construction constructor for body-logic unit tests (no lease). */
  public SavedSearchMatchingService(SavedSearchRepository savedSearches,
                                    ItemService itemService,
                                    NotificationService notifications) {
    this(savedSearches, itemService, notifications, JobLockGuard.disabled());
  }

  /** What one matcher run did: searches scanned, rows baselined, notifications written, total new matches. */
  public record MatchCounts(int searches, int baselined, int notifications, int newMatches) {}

  /** Runs every 15 minutes; also callable directly in tests. */
  @Scheduled(cron = "0 */15 * * * *")
  @Transactional
  public MatchCounts matchSavedSearches() {
    if (!jobLock.tryAcquire(LOCK_NAME)) {
      return new MatchCounts(0, 0, 0, 0);
    }
    try {
      return matchSavedSearchesLocked();
    } finally {
      // Inside this transaction the guard defers the actual release
      // until the transaction completes (see JobLockGuard.release).
      jobLock.release(LOCK_NAME);
    }
  }

  private MatchCounts matchSavedSearchesLocked() {
    int baselined = 0;
    int notified = 0;
    int totalNewMatches = 0;
    List<SavedSearch> searches = savedSearches.findAllByOrderByIdAsc();
    for (SavedSearch search : searches) {
      if (search.getLastNotifiedItemId() == null) {
        // Legacy row (predates the matcher): establish the baseline
        // only — listings that already exist are not "new" for it.
        search.advanceLastNotifiedItemId(itemService.highestItemId());
        savedSearches.save(search);
        baselined++;
        continue;
      }
      long watermark = search.getLastNotifiedItemId();
      long highestSeen = watermark;
      int newMatches = 0;
      int pageNumber = 0;
      while (true) {
        Page<ItemDto> page = itemService.listItems(
            PageRequest.of(pageNumber, PAGE_SIZE), search.getCategoryId(),
            search.getKeyword(), search.getMinPriceCents(), search.getMaxPriceCents(),
            search.getSort());
        for (ItemDto dto : page.getContent()) {
          if (dto.id() != null && dto.id() > highestSeen) {
            highestSeen = dto.id();
          }
          if (dto.id() != null && dto.id() > watermark
              && dto.status() == ItemStatus.AVAILABLE
              && (dto.sellerId() == null || !dto.sellerId().equals(search.getUserId()))) {
            newMatches++;
          }
        }
        if (!page.hasNext()) {
          break;
        }
        pageNumber++;
      }
      if (newMatches > 0) {
        // Record BEFORE advancing the watermark: if this write fails,
        // the watermark object is untouched and the run's transaction
        // rolls back — a failed run advances nothing.
        notifications.recordSavedSearchMatch(
            search.getUserId(), search.getId(), newMatches);
        notified++;
        totalNewMatches += newMatches;
      }
      if (highestSeen > watermark) {
        search.advanceLastNotifiedItemId(highestSeen);
        savedSearches.save(search);
      }
    }
    log.info("saved-search matching scanned {} searches ({} baselined), "
        + "wrote {} notifications for {} new matches",
        searches.size(), baselined, notified, totalNewMatches);
    return new MatchCounts(searches.size(), baselined, notified, totalNewMatches);
  }
}
