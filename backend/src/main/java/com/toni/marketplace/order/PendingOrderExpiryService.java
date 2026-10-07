package com.toni.marketplace.order;

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
 * Cancels PENDING orders that never got paid. A buyer who creates an order
 * and never pays (and never cancels, backlog #33) strands the listing in
 * RESERVED until they act — before this job there was no automatic release.
 * PENDING orders older than {@code app.orders.pending-ttl} are transitioned
 * to CANCELLED and their RESERVED listings flip back to AVAILABLE, in
 * batches.
 *
 * <p>Concurrency posture, mirroring {@link OrderService#expireStaleOrder}:
 * the job itself is NOT transactional — each order expires in its own
 * transaction through the {@link OrderService} proxy, so a concurrent pay
 * winning on one row cannot roll back the whole batch. When a concurrent
 * transition of the same order commits between the worklist scan and the
 * row transaction, the row's {@code @Version} check fails and the row is
 * skipped (the concurrent transition won legitimately; no retry, no crash
 * loop). When the concurrent transition commits before the row transaction
 * even starts, the per-row re-check sees a non-PENDING status and skips it
 * without touching anything.
 *
 * <p>Honest scope: the schedule fires in the JVM's default time zone (the
 * cron is fixed, not zone-pinned), and expiry granularity is the 5-minute
 * cadence — a stale order holds its listing at most TTL + one cadence.
 */
@Service
public class PendingOrderExpiryService {

  private static final Logger log = LoggerFactory.getLogger(PendingOrderExpiryService.class);

  /** Order ids re-read per repository call; keeps each select statement small. */
  static final int BATCH_SIZE = 500;

  private final OrderRepository orders;
  private final OrderService orderService;
  private final OrderExpiryProperties props;
  private final Clock clock;

  public PendingOrderExpiryService(OrderRepository orders, OrderService orderService,
                                   OrderExpiryProperties props, Clock clock) {
    this.orders = orders;
    this.orderService = orderService;
    this.props = props;
    this.clock = clock;
  }

  /** How many stale orders one expiry run cancelled vs. skipped. */
  public record ExpiryCounts(int expired, int skipped) {}

  /**
   * Runs every 5 minutes server-local. Also callable directly in tests with a
   * controllable {@link Clock}.
   */
  @Scheduled(cron = "0 */5 * * * *")
  public ExpiryCounts expireStalePendingOrders() {
    Instant cutoff = clock.instant().minus(props.getPendingTtl());
    int expired = 0;
    int skipped = 0;
    List<Long> ids;
    do {
      // Always re-read page 0: rows change status between passes (paid,
      // cancelled, or expired by this very loop), so a forward-moving page
      // index would skip rows.
      ids = orders.findIdsByStatusAndCreatedAtBefore(OrderStatus.PENDING, cutoff,
          PageRequest.of(0, BATCH_SIZE));
      for (Long id : ids) {
        try {
          if (orderService.expireStaleOrder(id, cutoff)) {
            expired++;
          } else {
            // Status or age changed between the scan and the row
            // transaction — nothing to do.
            skipped++;
          }
        } catch (ObjectOptimisticLockingFailureException e) {
          // A concurrent transition of this row committed after the row
          // transaction's read: its transition won, the order is no longer
          // ours to expire. Skipped, not failed.
          skipped++;
          log.info("expiry skipped order {}: a concurrent transition won", id);
        } catch (RuntimeException e) {
          // Defensive: one bad row must not stop the batch (the next run
          // retries it). Logged with the id — never silently swallowed.
          skipped++;
          log.warn("expiry failed for order {}", id, e);
        }
      }
    } while (ids.size() == BATCH_SIZE);
    log.info("pending-order expiry cancelled {} orders, skipped {}", expired, skipped);
    return new ExpiryCounts(expired, skipped);
  }
}
