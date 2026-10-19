package com.toni.marketplace.offer;

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
 * Expires live price offers nobody answered (backlog #168; COUNTERED
 * rows join the sweep in backlog #193 — a countered offer lapses on the
 * same creation deadline, countering buys no extra time). An
 * unanswered offer reserves nothing — the listing stays AVAILABLE and
 * buyable the whole time — so expiry here is bookkeeping, not a release:
 * the row flips PENDING / COUNTERED → EXPIRED so neither side's list
 * keeps showing a dead offer as live, and the (buyer, item) live pair
 * frees up for a fresh offer.
 *
 * <p>Concurrency posture, mirroring {@code PendingOrderExpiryService}:
 * the job itself is NOT transactional — each offer expires in its own
 * transaction through the {@link OfferService} proxy, so a concurrent
 * accept/decline/withdraw winning on one row cannot roll back the whole
 * batch. A row answered between the worklist scan and the row
 * transaction is skipped by the per-row re-check; a row answered
 * mid-transaction loses its {@code @Version} race and is likewise
 * skipped — never a crash loop.
 *
 * <p>Honest scope: the schedule fires in the JVM's default time zone
 * and expiry granularity is the 5-minute cadence, so a stale offer
 * outlives its deadline by at most TTL + one cadence in the list views;
 * the decision endpoints additionally check the deadline lazily (see
 * {@link OfferService}), so a stale offer can never be accepted in
 * that gap.
 */
@Service
public class OfferExpiryService {

  private static final Logger log = LoggerFactory.getLogger(OfferExpiryService.class);

  /** Offer ids re-read per repository call; keeps each select small. */
  static final int BATCH_SIZE = 500;

  private final PriceOfferRepository offers;
  private final OfferService offerService;
  private final Clock clock;

  public OfferExpiryService(PriceOfferRepository offers, OfferService offerService, Clock clock) {
    this.offers = offers;
    this.offerService = offerService;
    this.clock = clock;
  }

  /** How many stale offers one expiry run expired vs. skipped. */
  public record ExpiryCounts(int expired, int skipped) {}

  /** Runs every 5 minutes server-local; also callable directly in tests. */
  @Scheduled(cron = "0 */5 * * * *")
  public ExpiryCounts expireStaleOffers() {
    Instant now = clock.instant();
    int expired = 0;
    int skipped = 0;
    // Both live states lapse on the same deadline (#193): PENDING rows
    // the seller never answered and COUNTERED rows the buyer never
    // answered. Each status gets its own paged sweep.
    for (OfferStatus live : List.of(OfferStatus.PENDING, OfferStatus.COUNTERED)) {
      List<Long> ids;
      do {
        // Always re-read page 0: rows change status between passes
        // (answered, or expired by this very loop), so a forward-moving
        // page index would skip rows.
        ids = offers.findIdsByStatusAndExpiresAtBefore(live, now,
            PageRequest.of(0, BATCH_SIZE));
        for (Long id : ids) {
          try {
            if (offerService.expireStaleOffer(id, now)) {
              expired++;
            } else {
              skipped++;
            }
          } catch (ObjectOptimisticLockingFailureException e) {
            // A concurrent answer of this row committed after the row
            // transaction's read: its transition won, the offer is no
            // longer ours to expire. Skipped, not failed.
            skipped++;
            log.info("offer expiry skipped offer {}: a concurrent transition won", id);
          } catch (RuntimeException e) {
            // Defensive: one bad row must not stop the batch (the next
            // run retries it). Logged with the id — never swallowed.
            skipped++;
            log.warn("offer expiry failed for offer {}", id, e);
          }
        }
      } while (ids.size() == BATCH_SIZE);
    }
    log.info("offer expiry expired {} offers, skipped {}", expired, skipped);
    return new ExpiryCounts(expired, skipped);
  }
}
