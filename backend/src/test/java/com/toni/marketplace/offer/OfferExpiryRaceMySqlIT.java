package com.toni.marketplace.offer;

import static org.assertj.core.api.Assertions.assertThat;

import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.order.IdempotencyKeyRepository;
import com.toni.marketplace.order.Order;
import com.toni.marketplace.order.OrderRepository;
import com.toni.marketplace.order.OrderStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Offer-expiry-vs-accept race against a real MySQL 8 (Testcontainers),
 * driven through the scheduled job itself. Requires Docker; runs in CI
 * via {@code mvn verify -Pintegration}, not in the default unit-test
 * phase.
 *
 * <p>Backlog #286: the offer domain already has H2/MockMvc proof for
 * accept ({@code PriceOfferTest}) and unit proof for the expiry job's
 * counting and skip semantics ({@code OfferExpiryServiceTest}), but
 * the property that arbitrates a real race lives in the database —
 * the {@code @Version} fail-fast on the offer row, deciding between
 * the seller's accept (which creates the order at the agreed price and
 * flips the listing RESERVED) and the expiry job's per-row flip to
 * EXPIRED. Only this class races the two against InnoDB, released on
 * one latch, over repeated rounds.
 *
 * <p>Making the fixture stale: the job is constructed by hand with
 * the real repository and {@link OfferService} beans and a
 * {@link Clock} fixed at the offer's {@code expiresAt} + 1 minute —
 * so the offer is genuinely past its deadline by the job's own
 * arithmetic, without backdating any database row and without moving
 * the shared application clock for other beans. The three-argument
 * job constructor carries a disabled {@code JobLockGuard}, so the
 * race is about the offer row, never about lease contention. The
 * accept path itself keeps the real (system) clock, so its lazy
 * deadline check never fires here: the offer is stale only in the
 * job's clock, exactly the gap the job exists to close.
 *
 * <p>Invariant per round — exactly one terminal outcome, asserted on
 * BOTH rows afterwards:
 *
 * <ul>
 *   <li>accept won: offer ACCEPTED carrying the created order id, the
 *       order PENDING at the agreed (offered) price, the listing
 *       RESERVED, and the job expired nothing;</li>
 *   <li>expiry won: offer EXPIRED with no order id, no order row at
 *       all (the loser's order insert rolled back with its
 *       transaction), the listing still AVAILABLE, and accept failed
 *       fast with a client error (409 on the {@code @Version} flush
 *       check, or 422 when it read the winner's committed EXPIRED
 *       state first) — never a 5xx.</li>
 * </ul>
 *
 * <p>Honest scope / REPEATABLE-READ caveat, mirroring
 * {@code PendingOrderExpiryMySqlIT}: correctness never rests on the
 * job's worklist scan. Under InnoDB's default REPEATABLE READ the
 * scan is a snapshot hint taken in its own transaction — an accept
 * that commits after the scan still leaves the offer in the worklist,
 * and an accept that commits before it removes it. Both interleavings
 * are safe because the arbiter is the same as everywhere else in the
 * offer domain: the per-row transaction re-checks PENDING-and-stale
 * under its own read, and the {@code @Version} flush check decides
 * between two transactions that both read PENDING. The job's expired
 * count is therefore asserted as 0-or-1 per round and the winner is
 * decided by the XOR with the accept outcome, never by assuming the
 * scan saw a particular interleaving.
 */
@Testcontainers
@SpringBootTest
class OfferExpiryRaceMySqlIT {

  @Container
  @ServiceConnection
  static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  /** Rounds per run: enough for both winner orders to be plausible. */
  private static final int ROUNDS = 3;

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private OfferService offerService;

  @Autowired
  private PriceOfferRepository offers;

  @Autowired
  private OrderRepository orders;

  @Autowired
  private IdempotencyKeyRepository keys;

  @Autowired
  private NotificationRepository notifications;

  @Autowired
  private ItemRepository items;

  @Autowired
  private UserRepository users;

  @AfterEach
  void cleanUp() {
    notifications.deleteAll();
    offers.deleteAll();
    keys.deleteAll();
    orders.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User newUser() {
    String tag = "offer-race-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", "x"));
  }

  private Item newItem(User seller) {
    Item item = new Item("Race camera", "seeded", 10_000L, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    return items.save(item);
  }

  /** Accept's result: the committed transition, or the error it lost with. */
  private sealed interface AcceptOutcome permits AcceptOutcome.Won, AcceptOutcome.Lost {
    record Won(OfferDto offer) implements AcceptOutcome {}
    record Lost(HttpStatus status) implements AcceptOutcome {}
  }

  private AcceptOutcome acceptOutcome(CountDownLatch start, Long sellerId, Long offerId)
      throws Exception {
    start.await();
    try {
      return new AcceptOutcome.Won(offerService.accept(sellerId, offerId));
    } catch (ResponseStatusException e) {
      return new AcceptOutcome.Lost((HttpStatus) e.getStatusCode());
    }
  }

  private OfferExpiryService.ExpiryCounts expiryOutcome(
      CountDownLatch start, OfferExpiryService job) throws Exception {
    start.await();
    return job.expireStaleOffers();
  }

  private <T> T get(Future<T> future) throws Exception {
    try {
      return future.get(60, TimeUnit.SECONDS);
    } catch (java.util.concurrent.ExecutionException e) {
      throw new AssertionError("race thread died unexpectedly", e.getCause());
    }
  }

  @Test
  void scheduledExpiryJobVsAccept_exactlyOneTerminalOutcomePerRound() throws Exception {
    int acceptWins = 0;
    int expiryWins = 0;

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < ROUNDS; round++) {
        User seller = newUser();
        User buyer = newUser();
        Item item = newItem(seller);
        OfferDto created = offerService.createOffer(buyer.getId(), item.getId(), 8_000L);
        assertThat(created.status()).isEqualTo(OfferStatus.PENDING);

        // The real job, wired by hand only in its clock: the default
        // 48-hour TTL against a clock 1 minute past the offer's
        // deadline makes this one offer — the only PENDING row in the
        // database this round — genuinely stale for the job's scan.
        PriceOffer persisted = offers.findById(created.id()).orElseThrow();
        Clock jobClock = Clock.fixed(
            persisted.getExpiresAt().plus(Duration.ofMinutes(1)), ZoneOffset.UTC);
        OfferExpiryService job = new OfferExpiryService(offers, offerService, jobClock);

        CountDownLatch start = new CountDownLatch(1);
        Future<AcceptOutcome> accept =
            pool.submit(() -> acceptOutcome(start, seller.getId(), created.id()));
        Future<OfferExpiryService.ExpiryCounts> expiry =
            pool.submit(() -> expiryOutcome(start, job));
        start.countDown();

        AcceptOutcome acceptResult = get(accept);
        OfferExpiryService.ExpiryCounts expiryResult = get(expiry);

        boolean acceptWon = acceptResult instanceof AcceptOutcome.Won;
        boolean expiryWon = expiryResult.expired() == 1;
        // Exactly one winner: both winning would be an accepted offer
        // that also expired, neither winning a stranded PENDING offer.
        // The job can expire at most this one offer — it is the only
        // live row — so the count is 0 or 1 regardless of scan timing.
        assertThat(expiryResult.expired())
            .as("round %d expired count", round)
            .isIn(0, 1);
        assertThat(acceptWon)
            .as("round %d: exactly one of accept/expiry wins", round)
            .isNotEqualTo(expiryWon);

        PriceOffer finalOffer = offers.findById(created.id()).orElseThrow();
        Item finalItem = items.findById(item.getId()).orElseThrow();

        if (acceptWon) {
          acceptWins++;
          OfferDto won = ((AcceptOutcome.Won) acceptResult).offer();
          assertThat(won.status()).isEqualTo(OfferStatus.ACCEPTED);
          assertThat(won.orderId()).isNotNull();
          // The pair invariant, accept side: ACCEPTED + PENDING order
          // at the AGREED price + RESERVED, never ACCEPTED without an
          // order and never ACCEPTED + AVAILABLE.
          assertThat(finalOffer.getStatus()).isEqualTo(OfferStatus.ACCEPTED);
          assertThat(finalOffer.getOrderId()).isEqualTo(won.orderId());
          Order order = orders.findById(won.orderId()).orElseThrow();
          assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
          assertThat(order.getAmountCents()).isEqualTo(8_000L);
          assertThat(finalItem.getStatus()).isEqualTo(ItemStatus.RESERVED);
        } else {
          expiryWins++;
          AcceptOutcome.Lost lost = (AcceptOutcome.Lost) acceptResult;
          // The loser failed fast with a client error — never a 5xx.
          assertThat(lost.status())
              .isIn(HttpStatus.CONFLICT, HttpStatus.UNPROCESSABLE_ENTITY);
          // The pair invariant, expiry side: EXPIRED + no order +
          // AVAILABLE — the loser's order insert rolled back with its
          // transaction, so no order row survives for this round.
          assertThat(finalOffer.getStatus()).isEqualTo(OfferStatus.EXPIRED);
          assertThat(finalOffer.getOrderId()).isNull();
          assertThat(orders.count()).isZero();
          assertThat(finalItem.getStatus()).isEqualTo(ItemStatus.AVAILABLE);
        }
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(acceptWins + expiryWins).isEqualTo(ROUNDS);
  }
}
