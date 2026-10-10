package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Transition-race integration tests against a real MySQL 8
 * (Testcontainers). Requires Docker; runs in CI via
 * {@code mvn verify -Pintegration}, not in the default unit-test phase.
 *
 * <p>Backlog #107: the H2/MockMvc suites (#33/#49/#93, and the
 * pay-vs-expiry case in {@code OrderExpiryTest}) prove the
 * {@code @Version} fail-fast semantics in-process, and the MySQL IT
 * suite (#10/#11/#45) covers the create race and the pay-pay storm —
 * but neither race where the LOSER is a state transition that also
 * flips the listing. This class races, on one PENDING order:
 *
 * <ul>
 *   <li>cancel vs. pay — exactly one of {pay, cancel} wins;</li>
 *   <li>expiry ({@link OrderService#expireStaleOrder}) vs. pay —
 *       exactly one of {pay, expiry} wins.</li>
 * </ul>
 *
 * <p>The invariant being proven, asserted on BOTH rows after every
 * round: the final state is PAID + RESERVED (pay won) or CANCELLED +
 * AVAILABLE (cancel/expiry won) — never PAID + AVAILABLE and never
 * CANCELLED + RESERVED. The loser's failure is a client error, never
 * a 5xx: 409 when its {@code @Version} check fails at flush, 422 when
 * it reads the winner's already-committed state first. And the
 * loser's {@code orders.*} counter (#93, readable in-process via the
 * {@link MeterRegistry}) does not move: across all rounds each
 * counter's delta equals exactly the number of rounds that
 * transition won.
 */
@Testcontainers
@SpringBootTest
class OrderTransitionRaceMySqlIT {

  @Container
  @ServiceConnection
  static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  /** Rounds per race: enough for both winner orders to be plausible. */
  private static final int ROUNDS = 3;

  @Autowired
  private OrderService orderService;

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
    Item item = new Item("Race listing", "Campus pickup", 99000L, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    return items.save(item);
  }

  /** One racer's result: a committed transition, or the error it lost with. */
  private sealed interface Outcome permits Outcome.Won, Outcome.Lost {
    record Won(OrderStatus status) implements Outcome {}
    record Lost(HttpStatus status) implements Outcome {}
  }

  private Outcome payOutcome(CountDownLatch start, Long buyerId, Long orderId)
      throws Exception {
    start.await();
    try {
      return new Outcome.Won(orderService.pay(buyerId, orderId).status());
    } catch (ResponseStatusException e) {
      return new Outcome.Lost((HttpStatus) e.getStatusCode());
    }
  }

  private Outcome cancelOutcome(CountDownLatch start, Long buyerId, Long orderId)
      throws Exception {
    start.await();
    try {
      return new Outcome.Won(orderService.cancel(buyerId, orderId).status());
    } catch (ResponseStatusException e) {
      return new Outcome.Lost((HttpStatus) e.getStatusCode());
    }
  }

  /** Expiry result: {@code true} = this call expired the order (it won). */
  private enum ExpiryOutcome { EXPIRED, SKIPPED }

  private ExpiryOutcome expiryOutcome(CountDownLatch start, Long orderId, Instant cutoff)
      throws Exception {
    start.await();
    try {
      return orderService.expireStaleOrder(orderId, cutoff)
          ? ExpiryOutcome.EXPIRED
          : ExpiryOutcome.SKIPPED;
    } catch (ObjectOptimisticLockingFailureException e) {
      // The per-row contract PendingOrderExpiryService relies on: a
      // concurrent transition committed after this call's read, so the
      // row is skipped — the expiry lost, it did not fail.
      return ExpiryOutcome.SKIPPED;
    }
  }

  private <T> T get(Future<T> future) throws Exception {
    try {
      return future.get(60, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      throw new AssertionError("race thread died unexpectedly", e.getCause());
    }
  }

  private void assertConsistentFinalState(Long orderId, Long itemId,
                                          OrderStatus expectedOrderStatus) {
    Order finalOrder = orders.findById(orderId).orElseThrow();
    Item finalItem = items.findById(itemId).orElseThrow();
    assertThat(finalOrder.getStatus()).isEqualTo(expectedOrderStatus);
    // The pair invariant: PAID keeps the listing RESERVED, CANCELLED
    // releases it to AVAILABLE — the two half-written pairs
    // (PAID+AVAILABLE, CANCELLED+RESERVED) must never appear.
    ItemStatus expectedItemStatus = expectedOrderStatus == OrderStatus.PAID
        ? ItemStatus.RESERVED
        : ItemStatus.AVAILABLE;
    assertThat(finalItem.getStatus()).isEqualTo(expectedItemStatus);
  }

  @Test
  void cancelVsPayRace_exactlyOneTransitionWins_loserCounterUnmoved() throws Exception {
    double paidBefore = count("orders.paid");
    double cancelledBefore = count("orders.cancelled");
    int payWins = 0;
    int cancelWins = 0;

    for (int round = 0; round < ROUNDS; round++) {
      User seller = newUser("seller");
      User buyer = newUser("buyer");
      Item item = newItem(seller);
      OrderDto created = orderService.createOrder(
          buyer.getId(), item.getId(), "cancel-pay-race-" + UUID.randomUUID());
      assertThat(created.status()).isEqualTo(OrderStatus.PENDING);

      CountDownLatch start = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        Future<Outcome> pay = pool.submit(() -> payOutcome(start, buyer.getId(), created.id()));
        Future<Outcome> cancel =
            pool.submit(() -> cancelOutcome(start, buyer.getId(), created.id()));
        start.countDown();

        List<Outcome> outcomes = List.of(get(pay), get(cancel));
        List<Outcome.Won> winners = new ArrayList<>();
        List<Outcome.Lost> losers = new ArrayList<>();
        for (Outcome o : outcomes) {
          if (o instanceof Outcome.Won won) {
            winners.add(won);
          } else if (o instanceof Outcome.Lost lost) {
            losers.add(lost);
          }
        }

        // Exactly one transition committed; the loser failed fast with
        // a client error (409 version race, or 422 for reading the
        // winner's committed state) — never a 5xx, never a second win.
        assertThat(winners).as("round %d winners", round).hasSize(1);
        assertThat(losers).as("round %d losers", round).hasSize(1);
        assertThat(losers.get(0).status())
            .isIn(HttpStatus.CONFLICT, HttpStatus.UNPROCESSABLE_ENTITY);

        OrderStatus winningStatus = winners.get(0).status();
        assertThat(winningStatus).isIn(OrderStatus.PAID, OrderStatus.CANCELLED);
        assertConsistentFinalState(created.id(), item.getId(), winningStatus);
        if (winningStatus == OrderStatus.PAID) {
          payWins++;
        } else {
          cancelWins++;
        }
      } finally {
        pool.shutdownNow();
      }
    }

    assertThat(payWins + cancelWins).isEqualTo(ROUNDS);
    // Transitions, not calls (#93): each counter moved exactly once per
    // round its transition won — the loser's counter never moved.
    assertThat(count("orders.paid")).isEqualTo(paidBefore + payWins);
    assertThat(count("orders.cancelled")).isEqualTo(cancelledBefore + cancelWins);
  }

  @Test
  void expiryVsPayRace_exactlyOneTransitionWins_loserCounterUnmoved() throws Exception {
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
          buyer.getId(), item.getId(), "expiry-pay-race-" + UUID.randomUUID());
      assertThat(created.status()).isEqualTo(OrderStatus.PENDING);
      // Future cutoff: the just-created order counts as stale (the same
      // device OrderExpiryTest uses), so expiry is a real contender.
      Instant cutoff = Instant.now().plusSeconds(3600);

      CountDownLatch start = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        Future<Outcome> pay = pool.submit(() -> payOutcome(start, buyer.getId(), created.id()));
        Future<ExpiryOutcome> expiry =
            pool.submit(() -> expiryOutcome(start, created.id(), cutoff));
        start.countDown();

        Outcome payResult = get(pay);
        ExpiryOutcome expiryResult = get(expiry);

        boolean payWon = payResult instanceof Outcome.Won;
        boolean expiryWon = expiryResult == ExpiryOutcome.EXPIRED;
        // Exactly one winner: both winning would be a double
        // transition, neither winning a stranded PENDING order.
        assertThat(payWon)
            .as("round %d: exactly one of pay/expiry wins", round)
            .isNotEqualTo(expiryWon);
        if (!payWon) {
          Outcome.Lost lost = (Outcome.Lost) payResult;
          assertThat(lost.status())
              .isIn(HttpStatus.CONFLICT, HttpStatus.UNPROCESSABLE_ENTITY);
        }

        assertConsistentFinalState(created.id(), item.getId(),
            payWon ? OrderStatus.PAID : OrderStatus.CANCELLED);
        if (payWon) {
          payWins++;
        } else {
          expiryWins++;
        }
      } finally {
        pool.shutdownNow();
      }
    }

    assertThat(payWins + expiryWins).isEqualTo(ROUNDS);
    assertThat(count("orders.paid")).isEqualTo(paidBefore + payWins);
    assertThat(count("orders.expired")).isEqualTo(expiredBefore + expiryWins);
    // Expiry is a system transition (#93): a lost or won expiry never
    // inflates the buyer-cancel counter.
    assertThat(count("orders.cancelled")).isEqualTo(cancelledBefore);
  }
}
