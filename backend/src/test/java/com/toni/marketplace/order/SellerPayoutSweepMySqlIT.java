package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The seller-payout sweep against a real MySQL 8 (Testcontainers),
 * driven through the service seam. Requires Docker; runs in CI via
 * {@code mvn verify -Pintegration}, not in the default unit-test phase.
 *
 * <p>Backlog #326. The H2 {@code SellerPayoutTest} already pins the
 * sweep's promote-then-pay discipline, the dispute gate and the
 * seller-account hold — but none of the {@code *IT} classes covered
 * payouts, so the sweep's InnoDB behaviour (REPEATABLE READ snapshots,
 * the {@code @Version} flush check, the job-lease row) was unproven on
 * the dialect production actually runs. This class pins the same
 * semantics on MySQL: promotion only past the 7-day dispute window,
 * payment only on a later run, held rows never paid, a rerun paying
 * nothing twice, and no settlement row for a fully-refunded
 * completion.
 *
 * <p>Spec correction, verified before writing: the backlog text says
 * the *IT list "holds 12 classes". It held 14 by the time this class
 * was written ({@code OfferAcceptCounterAcceptMySqlIT} #324 and
 * {@code SavedSearchMatchMySqlIT} #321 landed after the item was
 * queued) — still none covering payouts, so the gap itself is real
 * and this class closes it.
 *
 * <p>On the "concurrent transition" leg: two sweep runs cannot race
 * each other inside one JVM — the #215 {@code JobLockGuard} lease
 * makes the second run skip with zero counts before it ever reads a
 * payout row, and that is the intended single-instance behaviour, not
 * a hole to drill through. The genuine optimistic-locking path is a
 * sweep racing an independent per-row transition (another writer
 * calling {@code SellerPayoutTransitions.promoteToPayable} directly).
 * The sweep's counts land in exactly the set its snapshot order
 * allows, because it reads the PAYABLE ids and then the PENDING ids
 * in two separate queries — a direct promotion can land at four
 * moments relative to those snapshots, and all four are legitimate,
 * never a double promotion: the sweep commits first (promoted 1,
 * final PAYABLE); the direct writer commits after the PENDING
 * snapshot, so the sweep's promotion returns false or loses the
 * flush race (skipped 1, final PAYABLE); the direct writer commits
 * before both snapshots, so the row is already PAYABLE when the run
 * starts and the sweep legitimately pays it in the same run
 * (paid 1, final PAID); or the direct writer commits between the
 * two snapshot queries, so the row is in neither list (all zero,
 * final PAYABLE). The test pins that whole set, plus the invariant
 * that the sweep never throws and exactly one row exists.
 *
 * <p>Fixtures are seeded directly through the repositories rather
 * than the HTTP API: the sweep reads orders, payouts and seller
 * rows, never tokens, and seeding a COMPLETED order with a
 * backdated {@code completedAt} is the only way to place a row past
 * the dispute window without waiting seven days. The creation leg
 * still goes through {@code SellerPayoutService
 * .createForCompletedOrder}, the production seam.
 */
@Testcontainers
@SpringBootTest
class SellerPayoutSweepMySqlIT {

  @Container
  @ServiceConnection
  static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  /** Rounds per race: enough for both winner interleavings to be plausible. */
  private static final int ROUNDS = 3;

  /** Past the 7-day dispute window; the sweep's own arithmetic is window + completedAt. */
  private static final Instant PAST_WINDOW = Instant.now().plusSeconds(8L * 24 * 3600);

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private SellerPayoutService payoutService;

  @Autowired
  private SellerPayoutTransitions transitions;

  @Autowired
  private SellerPayoutRepository payouts;

  @Autowired
  private SellerPayoutAdjustmentRepository adjustments;

  @Autowired
  private OrderRepository orders;

  @Autowired
  private ItemRepository items;

  @Autowired
  private UserRepository users;

  @AfterEach
  void cleanUp() {
    adjustments.deleteAll();
    payouts.deleteAll();
    orders.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User newUser() {
    String tag = "payout-sweep-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", "x"));
  }

  private Item newItem(User seller) {
    Item item = new Item("Race camera", "seeded", 10_000L, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    return items.save(item);
  }

  /**
   * A COMPLETED order whose completion predates the sweep instant by
   * eight days, so the dispute window has passed by the sweep's own
   * {@code completedAt + window} arithmetic. {@code refundedCents}
   * lets the fully-refunded leg seed a zero-net completion.
   */
  private Order completedOrder(User seller, User buyer, Item item, long refundedCents) {
    Order order = new Order(item, buyer.getId(), 10_000L);
    order.setStatus(OrderStatus.COMPLETED);
    order.setCompletedAt(Instant.now().minusSeconds(8L * 24 * 3600));
    order.setRefundedAmountCents(refundedCents);
    return orders.save(order);
  }

  private SellerPayout payoutFor(Order order) {
    return payouts.findByOrderId(order.getId()).orElseThrow();
  }

  @Test
  void sweepSkipsInsideWindow_promotesPastWindow_paysOnlyOnNextRun() {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);
    Order order = completedOrder(seller, buyer, item, 0L);
    assertThat(payoutService.createForCompletedOrder(order)).isPresent();

    // A run one day after completion is still inside the 7-day
    // dispute window and must promote nothing.
    Instant insideWindow = order.getCompletedAt().plusSeconds(24 * 3600);
    SellerPayoutService.PayoutSweepCounts early = payoutService.runSweep(insideWindow);
    assertThat(early.promoted()).isEqualTo(0);
    assertThat(early.paid()).isEqualTo(0);
    assertThat(payoutFor(order).getStatus()).isEqualTo(PayoutStatus.PENDING);

    // Past the window: promoted, but PAYABLE is a real observable
    // state — the same run pays nothing.
    SellerPayoutService.PayoutSweepCounts promoted = payoutService.runSweep(PAST_WINDOW);
    assertThat(promoted.promoted()).isEqualTo(1);
    assertThat(promoted.paid()).isEqualTo(0);
    SellerPayout payable = payoutFor(order);
    assertThat(payable.getStatus()).isEqualTo(PayoutStatus.PAYABLE);
    assertThat(payable.getPayableAt()).isNotNull();
    assertThat(payable.getPaidAt()).isNull();

    // Only a later run pays the row that was already PAYABLE when the
    // run started.
    SellerPayoutService.PayoutSweepCounts paid = payoutService.runSweep(PAST_WINDOW);
    assertThat(paid.paid()).isEqualTo(1);
    SellerPayout done = payoutFor(order);
    assertThat(done.getStatus()).isEqualTo(PayoutStatus.PAID);
    assertThat(done.getPaidAt()).isNotNull();
  }

  @Test
  void rerunAfterPayment_paysNothingTwice_paidAtUnchanged() {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);
    Order order = completedOrder(seller, buyer, item, 0L);
    assertThat(payoutService.createForCompletedOrder(order)).isPresent();
    assertThat(payoutService.runSweep(PAST_WINDOW).promoted()).isEqualTo(1);
    assertThat(payoutService.runSweep(PAST_WINDOW).paid()).isEqualTo(1);
    Instant paidAt = payoutFor(order).getPaidAt();

    SellerPayoutService.PayoutSweepCounts rerun = payoutService.runSweep(PAST_WINDOW);
    assertThat(rerun.promoted()).isEqualTo(0);
    assertThat(rerun.paid()).isEqualTo(0);
    assertThat(payouts.count()).isEqualTo(1);
    assertThat(payouts.countBySellerIdAndStatus(seller.getId(), PayoutStatus.PAID))
        .isEqualTo(1);
    assertThat(payoutFor(order).getPaidAt()).isEqualTo(paidAt);
  }

  @Test
  void disabledSeller_neverPaid_heldCounts_reEnabledResumes() {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);
    Order order = completedOrder(seller, buyer, item, 0L);
    assertThat(payoutService.createForCompletedOrder(order)).isPresent();

    seller.setDisabled(true);
    users.save(seller);

    // Held at promotion: counted as held, not as a skip, still PENDING.
    SellerPayoutService.PayoutSweepCounts heldAtPromotion =
        payoutService.runSweep(PAST_WINDOW);
    assertThat(heldAtPromotion.promoted()).isEqualTo(0);
    assertThat(heldAtPromotion.held()).isEqualTo(1);
    assertThat(heldAtPromotion.skipped()).isEqualTo(0);
    assertThat(payoutFor(order).getStatus()).isEqualTo(PayoutStatus.PENDING);

    // The hold is not terminal: re-enable and the next sweep promotes,
    // then the following run pays.
    seller.setDisabled(false);
    users.save(seller);
    assertThat(payoutService.runSweep(PAST_WINDOW).promoted()).isEqualTo(1);
    assertThat(payoutService.runSweep(PAST_WINDOW).paid()).isEqualTo(1);
    assertThat(payoutFor(order).getStatus()).isEqualTo(PayoutStatus.PAID);
  }

  @Test
  void closedSellerPayableRow_heldAtPayTime_neverPaid() {
    // Backlog #266: closure tombstones the account with disabled=true
    // (plus an anonymized identity), so one flag is the hold for both
    // states — a closed seller's PAYABLE row must stay PAYABLE.
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);
    Order order = completedOrder(seller, buyer, item, 0L);
    assertThat(payoutService.createForCompletedOrder(order)).isPresent();
    assertThat(payoutService.runSweep(PAST_WINDOW).promoted()).isEqualTo(1);
    assertThat(payoutFor(order).getStatus()).isEqualTo(PayoutStatus.PAYABLE);

    seller.setDisabled(true);
    seller.setUsername("deleted-" + seller.getId());
    seller.setEmail("deleted-" + seller.getId() + "@deleted.invalid");
    users.save(seller);

    SellerPayoutService.PayoutSweepCounts held = payoutService.runSweep(PAST_WINDOW);
    assertThat(held.paid()).isEqualTo(0);
    assertThat(held.held()).isEqualTo(1);
    assertThat(payoutFor(order).getStatus()).isEqualTo(PayoutStatus.PAYABLE);
  }

  @Test
  void fullyRefundedCompletion_createsNoSettlementRow() {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);
    // Captured 10_000, refunded 10_000: net zero, nothing is owed.
    Order order = completedOrder(seller, buyer, item, 10_000L);

    assertThat(payoutService.createForCompletedOrder(order)).isEmpty();
    assertThat(payouts.count()).isEqualTo(0);

    // And the sweep has nothing to promote or pay for it either.
    SellerPayoutService.PayoutSweepCounts sweep = payoutService.runSweep(PAST_WINDOW);
    assertThat(sweep.promoted()).isEqualTo(0);
    assertThat(sweep.paid()).isEqualTo(0);
    assertThat(payouts.count()).isEqualTo(0);
  }

  @Test
  void sweepVsDirectPromotion_raceEndsPayableExactlyOnce_sweepNeverThrows()
      throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      cleanUp();
      User seller = newUser();
      User buyer = newUser();
      Item item = newItem(seller);
      Order order = completedOrder(seller, buyer, item, 0L);
      assertThat(payoutService.createForCompletedOrder(order)).isPresent();
      Long payoutId = payoutFor(order).getId();

      CountDownLatch start = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        Future<SellerPayoutService.PayoutSweepCounts> sweep =
            pool.submit(() -> {
              start.await();
              return payoutService.runSweep(PAST_WINDOW);
            });
        Future<Boolean> direct = pool.submit(() -> {
          start.await();
          try {
            return transitions.promoteToPayable(payoutId, PAST_WINDOW);
          } catch (ObjectOptimisticLockingFailureException e) {
            // Lost the flush race to the sweep: the expected loser path.
            return false;
          }
        });
        start.countDown();

        SellerPayoutService.PayoutSweepCounts counts;
        boolean directWon;
        try {
          counts = sweep.get(60, TimeUnit.SECONDS);
          directWon = direct.get(60, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
          throw new AssertionError("race thread died unexpectedly", e.getCause());
        }

        // Exactly one promotion happened, by whichever writer won:
        // the row is PAYABLE (or, when the direct writer landed before
        // both of the sweep's snapshots, legitimately PAID by the same
        // run), there is still exactly one row, and the sweep's counts
        // are one of the four interleavings the snapshot order allows
        // (see the class javadoc). No exception escapes the sweep in
        // any interleaving — a lost version race is a skip, never a
        // failure. The direct writer's result correlates exactly: it
        // reports a win only when the sweep did not promote, and a
        // loss only when the sweep did.
        SellerPayout finalPayout = payoutFor(order);
        assertThat(finalPayout.getPayableAt())
            .as("round %d payableAt", round)
            .isNotNull();
        assertThat(payouts.count()).as("round %d payout rows", round).isEqualTo(1);
        assertThat(counts.held()).as("round %d held", round).isEqualTo(0);
        assertThat(List.of(counts.promoted(), counts.paid(), counts.skipped()))
            .as("round %d (promoted, paid, skipped)", round)
            .isIn(List.of(1, 0, 0), List.of(0, 0, 1),
                List.of(0, 1, 0), List.of(0, 0, 0));
        if (counts.paid() == 1) {
          assertThat(finalPayout.getStatus()).as("round %d final status", round)
              .isEqualTo(PayoutStatus.PAID);
          assertThat(finalPayout.getPaidAt()).as("round %d paidAt", round).isNotNull();
        } else {
          assertThat(finalPayout.getStatus()).as("round %d final status", round)
              .isEqualTo(PayoutStatus.PAYABLE);
          assertThat(finalPayout.getPaidAt()).as("round %d paidAt", round).isNull();
        }
        if (directWon) {
          assertThat(counts.promoted()).as("round %d promoted when direct won", round)
              .isEqualTo(0);
        } else {
          assertThat(counts.promoted()).as("round %d promoted when direct lost", round)
              .isEqualTo(1);
        }
      } finally {
        pool.shutdownNow();
      }
    }
  }
}
