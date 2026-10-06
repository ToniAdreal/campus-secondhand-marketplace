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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Idempotency integration tests against a real MySQL 8 (Testcontainers).
 * Requires Docker; runs in CI via {@code mvn verify -Pintegration},
 * not in the default unit-test phase.
 *
 * <p>The MockMvc suite ({@code OrderIdempotencyTest}) proves the contract
 * against H2. What H2 cannot prove is the concurrency story: the
 * {@code uq_idem_user_key} unique constraint is the atomic arbiter when two
 * requests race with the same key, and its behavior under a real InnoDB
 * insert race is what {@link IdempotencyKeyService#reserve} /
 * {@link IdempotencyKeyService#reread} are designed around.
 *
 * <ul>
 *   <li>Sequential retry with the same key: the original order is returned
 *       every time, exactly one {@code orders} row and one
 *       {@code idempotency_key} row exist (Flyway V5 running on real
 *       MySQL 8, enum column and all).</li>
 *   <li>Same-key storm: four threads racing {@code OrderService.createOrder}
 *       with one buyer, one key, one item — exactly one order row, one key
 *       row, the key COMPLETED pointing at that order. Every thread either
 *       gets the winner's order id (replay) or a 409 (re-read while the
 *       winner was still in flight); no second order is ever created.</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest
class OrderIdempotencyMySqlIT {

  @Container
  @ServiceConnection
  static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

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
  private JdbcTemplate jdbc;

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
  void retriedSameKey_returnsOriginalOrder_singleOrderRow() {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    String key = "idem-" + UUID.randomUUID();

    Set<Long> returnedIds = new HashSet<>();
    for (int i = 0; i < 3; i++) {
      returnedIds.add(orderService.createOrder(buyer.getId(), item.getId(), key).id());
    }

    // Every retry returned the original order — never a second row, even
    // though the item now has an active order and creation would 409.
    assertThat(returnedIds).hasSize(1);

    List<Order> orderRows = orders.findAll();
    assertThat(orderRows).hasSize(1);
    assertThat(orderRows.get(0).getId()).isEqualTo(returnedIds.iterator().next());

    List<IdempotencyKey> keyRows = keys.findAll();
    assertThat(keyRows)
        .hasSize(1)
        .first().satisfies(k -> {
          assertThat(k.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
          assertThat(k.getOrderId()).isEqualTo(orderRows.get(0).getId());
          assertThat(k.getItemId()).isEqualTo(item.getId());
        });
  }

  @Test
  void concurrentSameKeyStorm_singleOrderRowOnRealInnoDb() throws Exception {
    // The insert race that IdempotencyKeyService.reserve is designed for is
    // arbitrated by uq_idem_user_key — prove the constraint Flyway V5 created
    // on this MySQL 8 matches the name the code relies on.
    Integer uqCount = jdbc.queryForObject(
        "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS"
            + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'idempotency_key'"
            + " AND CONSTRAINT_NAME = 'uq_idem_user_key' AND CONSTRAINT_TYPE = 'UNIQUE'",
        Integer.class);
    assertThat(uqCount).isEqualTo(1);

    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    String key = "idem-" + UUID.randomUUID();

    int threads = 4;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<Outcome>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
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

      List<Outcome> outcomes = new ArrayList<>();
      for (Future<Outcome> f : futures) {
        try {
          outcomes.add(f.get(30, TimeUnit.SECONDS));
        } catch (ExecutionException e) {
          throw new AssertionError("storm thread died unexpectedly", e.getCause());
        }
      }

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

  /** Per-thread result of the same-key storm. */
  private sealed interface Outcome permits Outcome.Ok, Outcome.Failed {
    record Ok(Long orderId) implements Outcome {}
    record Failed(ResponseStatusException error) implements Outcome {}
  }
}
