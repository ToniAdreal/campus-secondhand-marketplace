package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * Concurrency integration tests against a real MySQL 8 (Testcontainers).
 * Requires Docker; runs in CI via {@code mvn verify -Pintegration},
 * not in the default unit-test phase.
 *
 * <ul>
 *   <li>Two buyers racing {@code POST /api/orders} on one listing: the
 *       {@code uq_order_active_item} guard guarantees exactly one winner —
 *       the loser gets 409, never a second row.</li>
 *   <li>Optimistic locking on the {@code orders} row itself: two sessions
 *       racing a state transition on the same order, the stale one fails
 *       fast instead of last-writer-wins.</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest
class OrderConcurrencyMySqlIT {

  @Container
  @ServiceConnection
  static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  private static final List<OrderStatus> ACTIVE =
      List.of(OrderStatus.PENDING, OrderStatus.PAID);

  @Autowired
  private OrderService orderService;

  @Autowired
  private OrderRepository orders;

  @Autowired
  private ItemRepository items;

  @Autowired
  private UserRepository users;

  @Test
  void concurrentBuyersRaceOnOneItem_exactlyOneOrderSucceeds() throws Exception {
    User seller = users.save(new User("seller-race", "seller-race@example.com", "hash"));
    User buyerA = users.save(new User("buyer-race-a", "buyer-race-a@example.com", "hash"));
    User buyerB = users.save(new User("buyer-race-b", "buyer-race-b@example.com", "hash"));
    Item item = items.save(new Item("ThinkPad X1", "Campus pickup", 180000L, seller.getId()));

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<OrderDto> a =
          pool.submit(() -> race(start, buyerA.getId(), item.getId(), "race-key-a"));
      Future<OrderDto> b =
          pool.submit(() -> race(start, buyerB.getId(), item.getId(), "race-key-b"));
      start.countDown();

      List<OrderDto> winners = new ArrayList<>();
      List<ResponseStatusException> losers = new ArrayList<>();
      for (Future<OrderDto> f : List.of(a, b)) {
        try {
          winners.add(f.get(30, TimeUnit.SECONDS));
        } catch (ExecutionException e) {
          assertThat(e.getCause()).isInstanceOf(ResponseStatusException.class);
          losers.add((ResponseStatusException) e.getCause());
        }
      }

      assertThat(winners).hasSize(1);
      assertThat(losers).hasSize(1);
      // Loser path is either the application-level active-order check or the
      // uq_order_active_item constraint; both surface as 409.
      assertThat(losers.get(0).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

      long rowsForItem = orders.findAll().stream()
          .filter(o -> o.getItem().getId().equals(item.getId()))
          .count();
      assertThat(rowsForItem).isEqualTo(1);
      assertThat(orders.existsByItem_IdAndStatusIn(item.getId(), ACTIVE)).isTrue();
    } finally {
      pool.shutdownNow();
    }
  }

  private OrderDto race(CountDownLatch start, Long buyerId, Long itemId, String key)
      throws Exception {
    start.await();
    return orderService.createOrder(buyerId, itemId, key);
  }

  @Test
  void optimisticLockingProof_staleTransitionOnSameOrder_failsFast() {
    User seller = users.save(new User("seller-ol", "seller-ol@example.com", "hash"));
    User buyer = users.save(new User("buyer-ol", "buyer-ol@example.com", "hash"));
    Item item = items.save(new Item("iPad Air", "Campus pickup", 220000L, seller.getId()));

    OrderDto created = orderService.createOrder(buyer.getId(), item.getId(), "ol-key");

    // Two sessions read the same row at version 0.
    Order first = orders.findById(created.id()).orElseThrow();
    Order second = orders.findById(created.id()).orElseThrow();
    assertThat(first.getVersion()).isEqualTo(0L);
    assertThat(second.getVersion()).isEqualTo(0L);

    // First transition commits (version 0 -> 1).
    first.setStatus(OrderStatus.PAID);
    orders.saveAndFlush(first);
    assertThat(orders.findById(created.id()).orElseThrow().getVersion()).isEqualTo(1L);

    // The stale copy still carries version 0: merging it must fail fast
    // instead of silently overwriting the PAID transition.
    second.setStatus(OrderStatus.CANCELLED);
    assertThatThrownBy(() -> orders.saveAndFlush(second))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }
}
