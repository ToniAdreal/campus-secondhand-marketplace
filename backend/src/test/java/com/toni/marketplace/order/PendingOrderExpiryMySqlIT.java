package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Expiry-vs-pay race against a real MySQL 8 (Testcontainers), driven
 * through the scheduled job itself. Requires Docker; runs in CI via
 * {@code mvn verify -Pintegration}, not in the default unit-test phase.
 *
 * <p>Backlog #135: the expiry-vs-pay race already has MySQL proof in
 * {@link OrderTransitionRaceMySqlIT} (#107) — but that class calls the
 * per-row step {@link OrderService#expireStaleOrder} directly. The code
 * that actually runs every five minutes in the deployment is
 * {@link PendingOrderExpiryService#expireStalePendingOrders()}: a batch
 * worklist scan ({@code findIdsByStatusAndCreatedAtBefore}) followed by
 * the per-row step, with the job's own per-row skip handling around it.
 * Only this class exercises that whole path against InnoDB, racing one
 * full job run against one {@code OrderService.pay} on the same PENDING
 * order, released on one latch, over repeated rounds.
 *
 * <p>Making the fixture stale: the job is constructed by hand with the
 * real repository and {@link OrderService} beans, the default
 * {@link OrderExpiryProperties} (30-minute TTL), and a {@link Clock}
 * fixed at the order's {@code createdAt} + 31 minutes — so the order is
 * genuinely past its TTL by the job's own arithmetic (cutoff =
 * {@code createdAt} + 1 minute), without backdating any database row
 * ({@code created_at} is {@code updatable = false}) and without moving
 * the shared application clock for other beans.
 *
 * <p>Invariant per round — exactly one terminal outcome, asserted on
 * BOTH rows afterwards:
 *
 * <ul>
 *   <li>pay won: order PAID + listing RESERVED, the job expired
 *       nothing, and exactly one logical capture exists under the
 *       order's PSP key — {@link PaymentService#capturesIssued()} moves
 *       by exactly one and the persisted row carries its
 *       {@code cap_mock_*} capture id and {@code cap_*} key
 *       (backlog #65/#114);</li>
 *   <li>expiry won: order CANCELLED + listing AVAILABLE, the job
 *       expired exactly the one order, pay failed with a client error
 *       (409 on the {@code @Version} flush check, or 422 when it read
 *       the winner's committed CANCELLED state first) — never a 5xx —
 *       and the persisted row carries no capture id.</li>
 * </ul>
 *
 * <p>Honest scope / REPEATABLE-READ caveat: correctness never rests on
 * the job's worklist scan. Under InnoDB's default REPEATABLE READ the
 * scan is a snapshot hint taken in its own transaction — a pay that
 * commits after the scan still leaves the order in the worklist, and a
 * pay that commits before it removes it. Both interleavings are safe
 * because the arbiter is the same as everywhere else in the order
 * domain: the per-row transaction re-checks PENDING-and-stale under
 * its own read, and the {@code @Version} flush check decides between
 * two transactions that both read PENDING. One consequence shapes the
 * assertions above: when expiry wins, pay may already have called the
 * mock capture before its version check failed at flush — the mock's
 * {@code capturesIssued} counter is not transactional and survives
 * that rollback, so a capture count is asserted only for rounds pay
 * actually won (where it must be exactly one), never for rounds it
 * lost (where it may legitimately be zero or one; the rolled-back row
 * provably stores no capture id either way).
 */
@Testcontainers
@SpringBootTest
class PendingOrderExpiryMySqlIT {

  @Container
  @ServiceConnection
  static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  /** Rounds per run: enough for both winner orders to be plausible. */
  private static final int ROUNDS = 3;

  @Autowired
  private OrderService orderService;

  @Autowired
  private PaymentService payments;

  @Autowired
  private OrderRepository orders;

  @Autowired
  private IdempotencyKeyRepository keys;

  @Autowired
  private ItemRepository items;

  @Autowired
  private UserRepository users;

  @Autowired
  private MeterRegistry registry;

  @AfterEach
  void cleanUp() {
    // FK-safe order: key rows reference orders and users.
    keys.deleteAll();
    orders.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private double count(String name) {
    Counter counter = registry.find(name).counter();
    return counter == null ? 0.0 : counter.count();
  }

  private User newUser(String role) {
    String tag = role + "-" + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", "hash"));
  }

  private Item newItem(User seller) {
    Item item = new Item("Expiry race listing", "Campus pickup", 99000L, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    return items.save(item);
  }

  /** Pay's result: the committed transition, or the error it lost with. */
  private sealed interface PayOutcome permits PayOutcome.Won, PayOutcome.Lost {
    record Won(OrderStatus status) implements PayOutcome {}
    record Lost(HttpStatus status) implements PayOutcome {}
  }

  private PayOutcome payOutcome(CountDownLatch start, Long buyerId, Long orderId)
      throws Exception {
    start.await();
    try {
      return new PayOutcome.Won(orderService.pay(buyerId, orderId).status());
    } catch (ResponseStatusException e) {
      return new PayOutcome.Lost((HttpStatus) e.getStatusCode());
    }
  }

  private PendingOrderExpiryService.ExpiryCounts expiryOutcome(
      CountDownLatch start, PendingOrderExpiryService job) throws Exception {
    start.await();
    return job.expireStalePendingOrders();
  }

  private <T> T get(Future<T> future) throws Exception {
    try {
      return future.get(60, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      throw new AssertionError("race thread died unexpectedly", e.getCause());
    }
  }

  @Test
  void scheduledExpiryJobVsPay_exactlyOneTerminalOutcomePerRound() throws Exception {
    double paidBefore = count("orders.paid");
    double expiredBefore = count("orders.expired");
    double cancelledBefore = count("orders.cancelled");
    int payWins = 0;
    int expiryWins = 0;

    for (int round = 0; round < ROUNDS; round++) {
      User seller = newUser("seller");
      User buyer = newUser("buyer");
      Item item = newItem(seller);
      OrderDto created = orderService.createOrder(
          buyer.getId(), item.getId(), "job-expiry-pay-race-" + UUID.randomUUID());
      assertThat(created.status()).isEqualTo(OrderStatus.PENDING);

      // The real job, wired by hand only in its clock: the default
      // 30-minute TTL against a clock 31 minutes past the order's
      // creation makes this one order — the only PENDING row in the
      // database this round — genuinely stale for the job's scan.
      Order persisted = orders.findById(created.id()).orElseThrow();
      Clock jobClock = Clock.fixed(
          persisted.getCreatedAt().plus(Duration.ofMinutes(31)), ZoneOffset.UTC);
      PendingOrderExpiryService job = new PendingOrderExpiryService(
          orders, orderService, new OrderExpiryProperties(), jobClock);

      long capturesBefore = payments.capturesIssued();

      CountDownLatch start = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        Future<PayOutcome> pay =
            pool.submit(() -> payOutcome(start, buyer.getId(), created.id()));
        Future<PendingOrderExpiryService.ExpiryCounts> expiry =
            pool.submit(() -> expiryOutcome(start, job));
        start.countDown();

        PayOutcome payResult = get(pay);
        PendingOrderExpiryService.ExpiryCounts expiryResult = get(expiry);

        boolean payWon = payResult instanceof PayOutcome.Won;
        boolean expiryWon = expiryResult.expired() == 1;
        // Exactly one winner: both winning would be a double
        // transition, neither winning a stranded PENDING order. The
        // job can expire at most this one order — it is the only row.
        assertThat(expiryResult.expired())
            .as("round %d expired count", round)
            .isIn(0, 1);
        assertThat(payWon)
            .as("round %d: exactly one of pay/expiry wins", round)
            .isNotEqualTo(expiryWon);

        Order finalOrder = orders.findById(created.id()).orElseThrow();
        Item finalItem = items.findById(item.getId()).orElseThrow();

        if (payWon) {
          payWins++;
          assertThat(((PayOutcome.Won) payResult).status()).isEqualTo(OrderStatus.PAID);
          // The pair invariant, pay side: PAID + RESERVED, never
          // PAID + AVAILABLE.
          assertThat(finalOrder.getStatus()).isEqualTo(OrderStatus.PAID);
          assertThat(finalItem.getStatus()).isEqualTo(ItemStatus.RESERVED);
          // Exactly one logical capture under this order's PSP key
          // (#65): the counter moved once, and the persisted row names
          // the capture it would be reconciled by (#114).
          assertThat(payments.capturesIssued()).isEqualTo(capturesBefore + 1);
          assertThat(finalOrder.getCaptureIdempotencyKey()).startsWith("cap_");
          assertThat(finalOrder.getCaptureId()).startsWith("cap_mock_");
        } else {
          expiryWins++;
          PayOutcome.Lost lost = (PayOutcome.Lost) payResult;
          // The loser failed fast with a client error — never a 5xx.
          assertThat(lost.status())
              .isIn(HttpStatus.CONFLICT, HttpStatus.UNPROCESSABLE_ENTITY);
          // The pair invariant, expiry side: CANCELLED + AVAILABLE,
          // never CANCELLED + RESERVED — and no capture id survived
          // on the row (a pre-flush capture rolled back with pay).
          assertThat(finalOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
          assertThat(finalItem.getStatus()).isEqualTo(ItemStatus.AVAILABLE);
          assertThat(finalOrder.getCaptureId()).isNull();
        }
      } finally {
        pool.shutdownNow();
      }
    }

    assertThat(payWins + expiryWins).isEqualTo(ROUNDS);
    // Transitions, not calls (#93): each counter moved exactly once per
    // round its transition won through the job path.
    assertThat(count("orders.paid")).isEqualTo(paidBefore + payWins);
    assertThat(count("orders.expired")).isEqualTo(expiredBefore + expiryWins);
    // Expiry is a system transition: neither outcome inflates the
    // buyer-cancel counter.
    assertThat(count("orders.cancelled")).isEqualTo(cancelledBefore);
  }
}
