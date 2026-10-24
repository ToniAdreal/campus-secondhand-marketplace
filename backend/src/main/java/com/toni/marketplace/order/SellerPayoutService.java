package com.toni.marketplace.order;

import com.toni.marketplace.job.JobLockGuard;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The seller payout ledger (backlog #221): one settlement row per
 * completed order, a dispute-window gate, an hourly sweep, and a
 * compensating-adjustment ledger for refunds after completion.
 *
 * <p>Lifecycle, in full:
 * <ul>
 *   <li><b>Creation</b> — {@link #createForCompletedOrder} runs inside
 *       {@code OrderService.complete}'s transaction (REQUIRED, never
 *       REQUIRES_NEW: a payout must never commit for a completion that
 *       rolls back). Amount = captured minus cumulative refunds at
 *       completion; no platform fee is invented, the mock takes none.
 *       An amount of zero or less creates no row — nothing is owed —
 *       and an existing row is returned unchanged, so a double call
 *       (defensive; re-complete already returns early) cannot duplicate.</li>
 *   <li><b>Promotion</b> — the sweep moves PENDING → PAYABLE once the
 *       #169 dispute window (default 7 days, {@code app.disputes.window})
 *       has passed since completion with no open dispute. A disputed
 *       order waits; a release resolution lets the next sweep promote
 *       it, a refund resolution leaves the row to be netted by its
 *       adjustment (see below) and never paid.</li>
 *   <li><b>Payment</b> — the same sweep moves PAYABLE → PAID, but only
 *       rows that were already PAYABLE when the run started. A row
 *       promoted in this run waits for the next, so PAYABLE is a real
 *       observable state. Honest scope, stated in the README row too:
 *       PAID is a ledger state — the mock PSP moves no real money, so
 *       no bank transfer, payout provider call, or fee settlement
 *       exists behind it.</li>
 *   <li><b>Adjustments</b> — {@link #recordRefundAdjustment} appends a
 *       negative entry instead of mutating the settlement row. In this
 *       codebase the only refund that can target a completed order is
 *       a dispute resolution (seller refunds require PAID), so that is
 *       the wired caller; the method itself is path-agnostic.</li>
 *   <li><b>Single instance</b> — the sweep takes the {@code #215}
 *       {@link JobLockGuard} lease first; a second instance skips with
 *       zero counts, mirroring the other mutating jobs.</li>
 * </ul>
 */
@Service
public class SellerPayoutService {

  private static final Logger log = LoggerFactory.getLogger(SellerPayoutService.class);

  /** Single-instance lease name for the payout sweep (backlog #215). */
  public static final String LOCK_NAME = "seller-payout-sweep";

  private final SellerPayoutRepository payouts;
  private final SellerPayoutAdjustmentRepository adjustments;
  private final SellerPayoutTransitions transitions;
  private final Clock clock;
  private final JobLockGuard jobLock;

  @Autowired
  public SellerPayoutService(SellerPayoutRepository payouts,
                             SellerPayoutAdjustmentRepository adjustments,
                             SellerPayoutTransitions transitions,
                             Clock clock,
                             JobLockGuard jobLock) {
    this.payouts = payouts;
    this.adjustments = adjustments;
    this.transitions = transitions;
    this.clock = clock;
    this.jobLock = jobLock;
  }

  /** Direct-construction constructor for body-logic unit tests (no lease). */
  public SellerPayoutService(SellerPayoutRepository payouts,
                             SellerPayoutAdjustmentRepository adjustments,
                             SellerPayoutTransitions transitions,
                             Clock clock) {
    this(payouts, adjustments, transitions, clock, JobLockGuard.disabled());
  }

  /** How many rows one sweep run promoted, paid and skipped. */
  public record PayoutSweepCounts(int promoted, int paid, int skipped) {}

  /**
   * Creates the settlement row for a freshly completed order. Joins the
   * caller's transaction — never its own — so creation and completion
   * commit or roll back together.
   */
  @Transactional
  public Optional<SellerPayout> createForCompletedOrder(Order order) {
    Optional<SellerPayout> existing = payouts.findByOrderId(order.getId());
    if (existing.isPresent()) {
      return existing;
    }
    long amount = order.getAmountCents() - order.getRefundedAmountCents();
    if (amount <= 0) {
      return Optional.empty();
    }
    return Optional.of(payouts.save(
        new SellerPayout(order.getId(), order.getItem().getSellerId(), amount)));
  }

  /**
   * Appends a compensating negative entry for a refund that landed after
   * completion. Never mutates the settlement row. Returns empty when the
   * order has no settlement row (never completed, or a zero-amount
   * completion) — there is nothing to compensate.
   */
  @Transactional
  public Optional<SellerPayoutAdjustment> recordRefundAdjustment(
      Long orderId, long refundAmountCents, String reason) {
    Optional<SellerPayout> settlement = payouts.findByOrderId(orderId);
    if (settlement.isEmpty() || refundAmountCents <= 0) {
      return Optional.empty();
    }
    return Optional.of(adjustments.save(new SellerPayoutAdjustment(
        settlement.get().getId(), orderId, settlement.get().getSellerId(),
        -refundAmountCents, reason)));
  }

  /** The seller's payout rows, newest first, with per-row adjustment sums. */
  @Transactional(readOnly = true)
  public Page<SellerPayoutDto> listForSeller(Long sellerId, org.springframework.data.domain.Pageable pageable) {
    Page<SellerPayout> page = payouts.findBySellerId(sellerId, pageable);
    List<Long> ids = page.getContent().stream().map(SellerPayout::getId).toList();
    Map<Long, Long> byPayout = ids.isEmpty() ? Map.of()
        : adjustments.findByPayoutIdIn(ids).stream()
            .collect(Collectors.groupingBy(SellerPayoutAdjustment::getPayoutId,
                Collectors.summingLong(SellerPayoutAdjustment::getAmountCents)));
    return page.map(p -> SellerPayoutDto.from(p, byPayout.getOrDefault(p.getId(), 0L)));
  }

  /** Totals over one seller's ledger; net = settlements + adjustments. */
  @Transactional(readOnly = true)
  public SellerPayoutTotalsDto totalsForSeller(Long sellerId) {
    return SellerPayoutTotalsDto.of(
        payouts.countBySellerIdAndStatus(sellerId, PayoutStatus.PENDING),
        payouts.sumAmountBySellerIdAndStatus(sellerId, PayoutStatus.PENDING),
        payouts.countBySellerIdAndStatus(sellerId, PayoutStatus.PAYABLE),
        payouts.sumAmountBySellerIdAndStatus(sellerId, PayoutStatus.PAYABLE),
        payouts.countBySellerIdAndStatus(sellerId, PayoutStatus.PAID),
        payouts.sumAmountBySellerIdAndStatus(sellerId, PayoutStatus.PAID),
        adjustments.sumAmountBySellerId(sellerId));
  }

  /** Runs every hour server-local. Also callable directly in tests. */
  @Scheduled(cron = "0 0 * * * *")
  public PayoutSweepCounts runScheduledSweep() {
    return runSweep(clock.instant());
  }

  /**
   * One sweep run at {@code now} (the parameter exists so tests can move
   * time past the dispute window without a MutableClock bean: promotion
   * compares the order's completed instant plus the window against it).
   */
  public PayoutSweepCounts runSweep(Instant now) {
    if (!jobLock.tryAcquire(LOCK_NAME)) {
      return new PayoutSweepCounts(0, 0, 0);
    }
    try {
      return runSweepLocked(now);
    } finally {
      jobLock.release(LOCK_NAME);
    }
  }

  private PayoutSweepCounts runSweepLocked(Instant now) {
    // Fixed id snapshots, not re-read pages: most PENDING rows are
    // legitimately unpromotable (window still open), so a re-read-page-0
    // loop would spin on them forever once the set exceeds one page.
    // Rows created mid-run are simply picked up by the next run, and
    // rows promoted here wait for the next run to be paid — PAYABLE
    // stays a real observable state.
    List<Long> payableIds = payouts.findIdsByStatus(PayoutStatus.PAYABLE);
    List<Long> pendingIds = payouts.findIdsByStatus(PayoutStatus.PENDING);

    int promoted = 0;
    int skipped = 0;
    for (Long payoutId : pendingIds) {
      try {
        if (transitions.promoteToPayable(payoutId, now)) {
          promoted++;
        } else {
          skipped++;
        }
      } catch (ObjectOptimisticLockingFailureException e) {
        skipped++;
        log.info("payout sweep skipped payout {}: a concurrent transition won", payoutId);
      }
    }

    int paid = 0;
    for (Long payoutId : payableIds) {
      try {
        if (transitions.markPaid(payoutId, now)) {
          paid++;
        } else {
          skipped++;
        }
      } catch (ObjectOptimisticLockingFailureException e) {
        skipped++;
        log.info("payout sweep skipped payout {}: a concurrent transition won", payoutId);
      }
    }
    return new PayoutSweepCounts(promoted, paid, skipped);
  }
}
