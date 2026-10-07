package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
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
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Pay-race integration test against a real MySQL 8 (Testcontainers).
 * Requires Docker; runs in CI via {@code mvn verify -Pintegration},
 * not in the default unit-test phase.
 *
 * <p>Eight threads race {@code OrderService.pay} on one PENDING order.
 * Exactly one capture must win: the final state is PAID, the listing is
 * RESERVED, and every loser fails fast with 409 — never a 5xx, never a
 * second transition, never a silent last-writer-wins overwrite. The
 * arbiter is the {@code @Version} optimistic-lock check on the order row
 * (the UPDATE's version predicate matches 0 rows for every loser).
 *
 * <p>Honest scope: the mock {@link PaymentService} is side-effect-free, so
 * "exactly one capture" is observed here as exactly one PENDING → PAID
 * transition, not by counting PSP calls — a mock {@code CaptureResult}
 * object is created in each racing thread before its version check fails
 * at flush, and no counter in the mock could tell you how many were
 * issued. In a real integration the losing thread's pre-flush capture call
 * is exactly why a PSP capture must carry an order-scoped idempotency key
 * (declared follow-up in {@code OrderService.pay}'s javadoc).
 */
@Testcontainers
@SpringBootTest
class OrderPayRaceMySqlIT {

  @Container
  @ServiceConnection
  static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  /** Enough racers to make the loser paths near-certain, cheap enough for CI. */
  private static final int RACERS = 8;

  @Autowired
  private OrderService orderService;

  @Autowired
  private OrderRepository orders;

  @Autowired
  private ItemRepository items;

  @Autowired
  private UserRepository users;

  @Test
  void payRaceOnOnePendingOrder_exactlyOneCaptureWins() throws Exception {
    User seller = users.save(new User("seller-pay-race", "seller-pay-race@example.com", "hash"));
    User buyer = users.save(new User("buyer-pay-race", "buyer-pay-race@example.com", "hash"));
    Item item = items.save(new Item("Sony WH-1000XM5", "Campus pickup", 150000L, seller.getId()));

    OrderDto created = orderService.createOrder(buyer.getId(), item.getId(), "pay-race-key");
    assertThat(created.status()).isEqualTo(OrderStatus.PENDING);

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(RACERS);
    try {
      List<Future<OrderDto>> futures = new ArrayList<>();
      for (int i = 0; i < RACERS; i++) {
        futures.add(pool.submit(() -> race(start, buyer.getId(), created.id())));
      }
      start.countDown();

      List<OrderDto> winners = new ArrayList<>();
      List<ResponseStatusException> losers = new ArrayList<>();
      for (Future<OrderDto> f : futures) {
        try {
          OrderDto dto = f.get(60, TimeUnit.SECONDS);
          assertThat(dto.status()).isEqualTo(OrderStatus.PAID);
          winners.add(dto);
        } catch (ExecutionException e) {
          assertThat(e.getCause()).isInstanceOf(ResponseStatusException.class);
          losers.add((ResponseStatusException) e.getCause());
        }
      }

      assertThat(winners).hasSize(1);
      assertThat(losers).hasSize(RACERS - 1);
      // Every loser must be the 409 fail-fast path — never a 5xx, never a
      // raw DataIntegrityViolation leaking through. The version check is
      // the only arbiter: no second row can exist, so no other status is
      // legitimate here.
      assertThat(losers)
          .allSatisfy(l -> assertThat(l.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

      Order finalOrder = orders.findById(created.id()).orElseThrow();
      assertThat(finalOrder.getStatus()).isEqualTo(OrderStatus.PAID);
      Item finalItem = items.findById(item.getId()).orElseThrow();
      assertThat(finalItem.getStatus()).isEqualTo(ItemStatus.RESERVED);
      long rowsForItem = orders.findAll().stream()
          .filter(o -> o.getItem().getId().equals(item.getId()))
          .count();
      assertThat(rowsForItem).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  private OrderDto race(CountDownLatch start, Long buyerId, Long orderId) throws Exception {
    start.await();
    return orderService.pay(buyerId, orderId);
  }
}
