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
import java.util.ArrayList;
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
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Accept-vs-counter-accept races on one COUNTERED offer against a real
 * MySQL 8 (Testcontainers), driven through the service seam. Requires
 * Docker; runs in CI via {@code mvn verify -Pintegration}, not in the
 * default unit-test phase.
 *
 * <p>Backlog #324. The offer *IT list held only
 * {@code OfferExpiryRaceMySqlIT} — no concurrent accept/counter-accept
 * coverage — while {@code OfferCounterTest} pins only the sequential
 * form on H2 (buy-now wins first, counter-accept then 409s).
 *
 * <p>Spec corrections, verified in source before writing this class —
 * the backlog text's race picture does not match the shipped
 * semantics, and this class pins the true behaviour instead of
 * pretending otherwise:
 * <ul>
 *   <li>Seller accept is PENDING-only by #193 design ("the decision
 *       has moved to the buyer"): on a COUNTERED offer it answers 422
 *       before ever reaching order creation, so it can never "win" a
 *       price race and its loser status is 422, not the spec's 409.
 *       The first test races it against the counter-accept anyway and
 *       pins exactly that exclusion on MySQL: the concurrent seller
 *       accept mints no second order and disturbs nothing.</li>
 *   <li>There is no COUNTER_ACCEPTED status: a counter-acceptance
 *       flips the offer to ACCEPTED, exactly like a seller accept.
 *       The winning price is observable on the created order (the
 *       counter price here), not on a distinct offer status.</li>
 *   <li>The genuine concurrent race over the order-creation seam is
 *       therefore two counter-accepts on one COUNTERED offer (second
 *       test): exactly one wins — ACCEPTED plus one order at the
 *       counter price plus RESERVED — and the loser answers 409 (the
 *       uq_order_active_item / @Version arbiters) or 422 (it read the
 *       winner's committed ACCEPTED state first), never a 5xx.</li>
 * </ul>
 *
 * <p>Race fix shipped with this class (backlog #324): the buy-now seam
 * ({@code OrderService.createOrder}) already mapped a lost
 * uq_order_active_item / version race to 409, but the offer seam
 * called {@code OrderCreationService} unwrapped — under InnoDB
 * REPEATABLE READ a loser whose snapshot predates the winner's commit
 * reaches the order insert before any version check, so its failure
 * would have fallen through to the generic 500. {@code OfferService}
 * now maps both failure modes to 409 around the creation call in
 * {@code accept()} and {@code counterAccept()}, so the "loser fails
 * fast with a client error" invariant holds at the service layer, not
 * just at the HTTP layer. The harness therefore maps a stray
 * {@code ObjectOptimisticLockingFailureException} to 409 as well
 * (the {@code GlobalExceptionHandler} mapping, for any flush path the
 * service wrap does not already cover), but deliberately does NOT
 * swallow a constraint violation: with the fix, no constraint failure
 * should escape the offer path, so one surfacing here is a real bug
 * and must fail loudly instead of being relabelled 409.
 *
 * <p>Per-round cleanup: each round starts from an empty database, so
 * the "exactly one order" assertions are scoped to the round and
 * cannot be polluted by an earlier round's winner — the cross-round
 * counting trap this suite's older races have to avoid.
 */
@Testcontainers
@SpringBootTest
class OfferAcceptCounterAcceptMySqlIT {

  @Container
  @ServiceConnection
  static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  /** Rounds per race: enough for both winner orders to be plausible. */
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
    String tag = "offer-accept-race-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", "x"));
  }

  private Item newItem(User seller) {
    Item item = new Item("Race camera", "seeded", 10_000L, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    return items.save(item);
  }

  /** One COUNTERED fixture: offered 7_000, countered at 9_000, ask 10_000. */
  private OfferDto newCounteredOffer(User seller, User buyer, Item item) {
    OfferDto created = offerService.createOffer(buyer.getId(), item.getId(), 7_000L);
    assertThat(created.status()).isEqualTo(OfferStatus.PENDING);
    OfferDto countered = offerService.counter(seller.getId(), created.id(), 9_000L);
    assertThat(countered.status()).isEqualTo(OfferStatus.COUNTERED);
    assertThat(countered.counterPriceCents()).isEqualTo(9_000L);
    return countered;
  }

  /** One racer's result: a committed transition, or the error it lost with. */
  private sealed interface Outcome permits Outcome.Won, Outcome.Lost {
    record Won(OfferDto offer) implements Outcome {}
    record Lost(HttpStatus status) implements Outcome {}
  }

  private Outcome acceptOutcome(CountDownLatch start, Long sellerId, Long offerId)
      throws Exception {
    start.await();
    try {
      return new Outcome.Won(offerService.accept(sellerId, offerId));
    } catch (ResponseStatusException e) {
      return new Outcome.Lost((HttpStatus) e.getStatusCode());
    } catch (ObjectOptimisticLockingFailureException e) {
      return new Outcome.Lost(HttpStatus.CONFLICT);
    }
  }

  private Outcome counterAcceptOutcome(CountDownLatch start, Long buyerId, Long offerId)
      throws Exception {
    start.await();
    try {
      return new Outcome.Won(offerService.counterAccept(buyerId, offerId));
    } catch (ResponseStatusException e) {
      return new Outcome.Lost((HttpStatus) e.getStatusCode());
    } catch (ObjectOptimisticLockingFailureException e) {
      return new Outcome.Lost(HttpStatus.CONFLICT);
    }
  }

  private <T> T get(Future<T> future) throws Exception {
    try {
      return future.get(60, TimeUnit.SECONDS);
    } catch (java.util.concurrent.ExecutionException e) {
      throw new AssertionError("race thread died unexpectedly", e.getCause());
    }
  }

  private void assertSingleCounterPriceOrder(int round, Long offerId, Long itemId,
                                             OfferDto won) {
    assertThat(won.status()).isEqualTo(OfferStatus.ACCEPTED);
    assertThat(won.orderId()).isNotNull();
    // The pair invariant, accept side: ACCEPTED + exactly one order at
    // the COUNTER price (never the offered price, never the asking
    // price) + RESERVED, never ACCEPTED without an order and never
    // ACCEPTED + AVAILABLE.
    assertThat(orders.count()).as("round %d order rows", round).isEqualTo(1);
    Order order = orders.findById(won.orderId()).orElseThrow();
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(order.getAmountCents()).isEqualTo(9_000L);
    PriceOffer finalOffer = offers.findById(offerId).orElseThrow();
    assertThat(finalOffer.getStatus()).isEqualTo(OfferStatus.ACCEPTED);
    assertThat(finalOffer.getOrderId()).isEqualTo(won.orderId());
    assertThat(items.findById(itemId).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.RESERVED);
  }

  @Test
  void sellerAcceptVsCounterAccept_counterAcceptWins_sellerAccept422_singleOrder()
      throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      cleanUp();
      User seller = newUser();
      User buyer = newUser();
      Item item = newItem(seller);
      OfferDto countered = newCounteredOffer(seller, buyer, item);

      CountDownLatch start = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        Future<Outcome> accept =
            pool.submit(() -> acceptOutcome(start, seller.getId(), countered.id()));
        Future<Outcome> counterAccept =
            pool.submit(() -> counterAcceptOutcome(start, buyer.getId(), countered.id()));
        start.countDown();

        Outcome acceptResult = get(accept);
        Outcome counterAcceptResult = get(counterAccept);

        // The seller's accept never reaches order creation on a
        // COUNTERED offer: the PENDING-only state guard answers 422
        // deterministically, whatever the interleaving. The buyer's
        // counter-accept always wins, so exactly one order exists and
        // it carries the counter price.
        assertThat(acceptResult)
            .as("round %d seller accept", round)
            .isEqualTo(new Outcome.Lost(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(counterAcceptResult).isInstanceOf(Outcome.Won.class);
        assertSingleCounterPriceOrder(round, countered.id(), item.getId(),
            ((Outcome.Won) counterAcceptResult).offer());
      } finally {
        pool.shutdownNow();
      }
    }
  }

  @Test
  void counterAcceptVsCounterAccept_exactlyOneOrder_loserFailsFast() throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      cleanUp();
      User seller = newUser();
      User buyer = newUser();
      Item item = newItem(seller);
      OfferDto countered = newCounteredOffer(seller, buyer, item);

      CountDownLatch start = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        Future<Outcome> first =
            pool.submit(() -> counterAcceptOutcome(start, buyer.getId(), countered.id()));
        Future<Outcome> second =
            pool.submit(() -> counterAcceptOutcome(start, buyer.getId(), countered.id()));
        start.countDown();

        List<Outcome> outcomes = List.of(get(first), get(second));
        List<Outcome.Won> winners = new ArrayList<>();
        List<Outcome.Lost> losers = new ArrayList<>();
        for (Outcome o : outcomes) {
          if (o instanceof Outcome.Won won) {
            winners.add(won);
          } else if (o instanceof Outcome.Lost lost) {
            losers.add(lost);
          }
        }

        // Exactly one acceptance committed; the loser failed fast with
        // a client error — 409 when its stale snapshot lost the
        // constraint/version race, 422 when it read the winner's
        // committed ACCEPTED state first — never a 5xx, never a
        // second order.
        assertThat(winners).as("round %d winners", round).hasSize(1);
        assertThat(losers).as("round %d losers", round).hasSize(1);
        assertThat(losers.get(0).status())
            .isIn(HttpStatus.CONFLICT, HttpStatus.UNPROCESSABLE_ENTITY);
        assertSingleCounterPriceOrder(round, countered.id(), item.getId(),
            winners.get(0).offer());
      } finally {
        pool.shutdownNow();
      }
    }
  }
}
