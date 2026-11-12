package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Order-creation concurrency integration tests on the local H2 dialect
 * (backlog #43).
 *
 * <p>The MySQL Testcontainers ITs ({@code OrderIdempotencyMySqlIT},
 * {@code OrderConcurrencyMySqlIT}) prove the same invariants on real InnoDB
 * in CI, and {@code OrderIdempotencyTest} proves the idempotency contract
 * against H2 — but only sequentially, by pre-inserting an IN_PROGRESS key
 * row. What no local test proved is the real multi-threaded race on the
 * dialect the default {@code mvn -o test} run actually uses: H2 arbitrates
 * the {@code uq_idem_user_key} insert race and the {@code Item.@Version}
 * flip race with its own locking, and the reserve/reread and
 * constraint/optimistic-locking failure paths in
 * {@link OrderService#createOrder} only earn their keep when threads truly
 * overlap. These tests close that gap without Docker.
 *
 * <p>Deliberately NOT {@code @Transactional}, same shape as
 * {@link OrderIdempotencyTest}: the idempotency-key lifecycle commits in
 * its own transactions ({@code REQUIRES_NEW}), so a wrapping test
 * transaction would hide the key rows from the reservation path.
 *
 * <p>Assertions are interleaving-proof on purpose: a thread that arrives
 * after the winner committed replays the winner's order id, one that
 * arrives while the winner is still in flight gets 409 — both are correct
 * outcomes, and the storm is only a failure if a second order row appears
 * or a thread sees anything else.
 */
@SpringBootTest
class OrderCreationConcurrencyTest {

  /** Enough racers to make the loser paths near-certain, cheap enough locally. */
  private static final int RACERS = 4;

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

  @AfterEach
  void cleanUp() {
    // FK-safe order: key rows reference orders and users.
    keys.deleteAll();
    orders.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private String tag(String role) {
    return role + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private User newUser(String role) {
    String t = tag(role);
    return users.save(new User(t, t + "@example.com", "hash"));
  }

  private Item newItem(User seller) {
    Item item = new Item("Film camera", "Canon AE-1", 88000L, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    return items.save(item);
  }

  @Test
  void concurrentSameKeyStorm_singleOrderRow() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    String key = "idem-" + UUID.randomUUID();

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(RACERS);
    try {
      List<Future<Outcome>> futures = new ArrayList<>();
      for (int i = 0; i < RACERS; i++) {
        futures.add(pool.submit(() -> {
          start.await();
          try {
            return new Outcome.Ok(orderService.createOrder(buyer.getId(), item.getId(), key).id());
          } catch (ResponseStatusException e) {
            return new Outcome.Failed(e);
          }
        }));
      }
      start.countDown();

      List<Outcome> outcomes = collect(futures);

      // Exactly one order row was created — the whole point of idempotency
      // under a retry storm (e.g. a client hammering after a timeout).
      List<Order> orderRows = orders.findAll();
      assertThat(orderRows).hasSize(1);
      Long orderId = orderRows.get(0).getId();

      List<IdempotencyKey> keyRows = keys.findAll();
      assertThat(keyRows)
          .hasSize(1)
          .first().satisfies(k -> {
            assertThat(k.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
            assertThat(k.getOrderId()).isEqualTo(orderId);
          });

      // Every thread saw a correct outcome: either the winner's order id
      // (replayed after the winner completed) or 409 (re-read while the
      // winner was still in flight — the client retries and replays).
      Set<Long> seenOrderIds = new HashSet<>();
      for (Outcome o : outcomes) {
        if (o instanceof Outcome.Ok ok) {
          seenOrderIds.add(ok.orderId());
        } else if (o instanceof Outcome.Failed failed) {
          assertThat(failed.error().getStatusCode())
              .as("non-replay storm outcomes must be 409 in-flight")
              .isEqualTo(HttpStatus.CONFLICT);
        }
      }
      assertThat(seenOrderIds).containsExactly(orderId);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void twoBuyersRaceOneListing_singleWinner_loserGets409() throws Exception {
    User seller = newUser("seller");
    List<User> buyers = List.of(newUser("buyer"), newUser("buyer"),
        newUser("buyer"), newUser("buyer"));
    Item item = newItem(seller);

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(RACERS);
    try {
      List<Future<Outcome>> futures = new ArrayList<>();
      for (User buyer : buyers) {
        String key = "idem-" + UUID.randomUUID();
        futures.add(pool.submit(() -> {
          start.await();
          try {
            return new Outcome.Ok(orderService.createOrder(buyer.getId(), item.getId(), key).id());
          } catch (ResponseStatusException e) {
            return new Outcome.Failed(e);
          }
        }));
      }
      start.countDown();

      List<Outcome> outcomes = collect(futures);

      // Exactly one order row, on exactly one buyer's key: the
      // uq_order_active_item constraint and the Item @Version flip
      // arbitrate the race, so the losers never insert.
      List<Order> orderRows = orders.findAll();
      assertThat(orderRows).hasSize(1);
      Long orderId = orderRows.get(0).getId();
      assertThat(orderRows.get(0).getStatus()).isEqualTo(OrderStatus.PENDING);

      // The listing flipped exactly once and stays RESERVED for the winner.
      Item reloaded = items.findById(item.getId()).orElseThrow();
      assertThat(reloaded.getStatus()).isEqualTo(ItemStatus.RESERVED);

      // One key per buyer: the winner's COMPLETED pointing at the order,
      // every loser's FAILED (burned by createOrder's race catch, so the
      // client retries with a NEW key instead of replaying a dead one).
      List<IdempotencyKey> keyRows = keys.findAll();
      assertThat(keyRows).hasSize(RACERS);
      assertThat(keyRows.stream().filter(k -> k.getStatus() == IdempotencyStatus.COMPLETED)
          .toList())
          .hasSize(1)
          .first().satisfies(k -> assertThat(k.getOrderId()).isEqualTo(orderId));
      assertThat(keyRows.stream().filter(k -> k.getStatus() == IdempotencyStatus.FAILED)
          .toList()).hasSize(RACERS - 1);

      // Exactly one winner; every loser got 409 (item already has an
      // active order — via the unique constraint, the stale @Version
      // flip, or the re-read guard, depending on interleaving).
      Set<Long> seenOrderIds = new HashSet<>();
      int losers = 0;
      for (Outcome o : outcomes) {
        if (o instanceof Outcome.Ok ok) {
          seenOrderIds.add(ok.orderId());
        } else if (o instanceof Outcome.Failed failed) {
          losers++;
          assertThat(failed.error().getStatusCode())
              .as("race losers must be 409")
              .isEqualTo(HttpStatus.CONFLICT);
        }
      }
      assertThat(seenOrderIds).containsExactly(orderId);
      assertThat(losers).isEqualTo(RACERS - 1);
    } finally {
      pool.shutdownNow();
    }
  }

  private List<Outcome> collect(List<Future<Outcome>> futures) throws Exception {
    List<Outcome> outcomes = new ArrayList<>();
    for (Future<Outcome> f : futures) {
      try {
        outcomes.add(f.get(30, TimeUnit.SECONDS));
      } catch (ExecutionException e) {
        throw new AssertionError("race thread died unexpectedly", e.getCause());
      }
    }
    return outcomes;
  }

  /** Per-thread result of a creation race. */
  private sealed interface Outcome permits Outcome.Ok, Outcome.Failed {
    record Ok(Long orderId) implements Outcome {}
    record Failed(ResponseStatusException error) implements Outcome {}
  }
}
