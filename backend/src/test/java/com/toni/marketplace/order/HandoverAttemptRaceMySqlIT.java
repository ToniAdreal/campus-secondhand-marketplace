package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import java.time.Duration;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Handover failed-attempt race integration tests against a real MySQL 8
 * (Testcontainers). Requires Docker; runs in CI via
 * {@code mvn verify -Pintegration}, not in the default unit-test
 * phase — this class states that here instead of being skipped
 * silently on a Docker-less machine.
 *
 * <p>Backlog #337: {@code HandoverAttemptService.recordFailure} was a
 * lock-free read-modify-write on the {@code @Version} order row, so a
 * burst of concurrent wrong guesses raced — measured on H2 before the
 * fix, 8 simultaneous failures recorded only 1–3 attempts and the
 * rest died with {@code ObjectOptimisticLockingFailureException}
 * (which the HTTP layer would have answered 409 instead of the
 * identical 400), and the 5-attempt lock never engaged. The row is
 * now read with a pessimistic write lock ({@code SELECT ... FOR
 * UPDATE}), so writers serialise: every increment lands and the lock
 * engages at the ceiling. This class pins that invariant on InnoDB,
 * where the locking read and the {@code @Version} check interact
 * differently than on H2; {@link HandoverAttemptConcurrencyTest}
 * pins the same invariant in the default unit-test phase.
 *
 * <ul>
 *   <li>Concurrent failures (repeated rounds, one latch per round):
 *       N simultaneous {@code recordFailure} calls against one order
 *       advance the counter by exactly N, none throws, and the lock
 *       is engaged (the ceiling is below N, so the lock must be set
 *       in every round).</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest
class HandoverAttemptRaceMySqlIT {

  @Container
  @ServiceConnection
  static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  /** Simultaneous failures per round: past the 5-attempt ceiling. */
  private static final int THREADS = 8;
  private static final int MAX_ATTEMPTS = 5;
  private static final int ROUNDS = 3;

  @Autowired private HandoverAttemptService attempts;
  @Autowired private OrderRepository orders;
  @Autowired private ItemRepository items;
  @Autowired private UserRepository users;

  @AfterEach
  void cleanUp() {
    orders.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private Order seedOrder() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    User seller = users.save(new User("seller-" + tag, "s-" + tag + "@example.com", "hash"));
    User buyer = users.save(new User("buyer-" + tag, "b-" + tag + "@example.com", "hash"));
    Item item = new Item("Race item", "seeded", 5000L, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    item = items.save(item);
    return orders.save(new Order(item, buyer.getId(), 5000L));
  }

  @Test
  void concurrentFailures_everyIncrementLands_lockEngagesAtCeiling() throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      Order order = seedOrder();
      Instant now = Instant.now();
      AtomicInteger failed = new AtomicInteger();
      List<String> failures = new ArrayList<>();

      CountDownLatch start = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(THREADS);
      try {
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
          futures.add(pool.submit(() -> {
            try {
              start.await();
              attempts.recordFailure(order.getId(), MAX_ATTEMPTS,
                  Duration.ofMinutes(15), now);
            } catch (Throwable t) {
              failed.incrementAndGet();
              synchronized (failures) {
                failures.add(t.getClass().getSimpleName());
              }
            }
            return null;
          }));
        }
        start.countDown();
        for (Future<?> f : futures) {
          try {
            f.get(60, TimeUnit.SECONDS);
          } catch (ExecutionException e) {
            throw new AssertionError("race thread died unexpectedly", e.getCause());
          }
        }
      } finally {
        pool.shutdownNow();
      }

      assertThat(failed.get())
          .as("round %d: recordFailure calls that threw %s", round, failures)
          .isZero();
      Order after = orders.findById(order.getId()).orElseThrow();
      assertThat(after.getHandoverFailedAttempts())
          .as("round %d: counter advanced by exactly %d", round, THREADS)
          .isEqualTo(THREADS);
      assertThat(after.getHandoverLockedUntil())
          .as("round %d: lock engaged at the %d-attempt ceiling", round, MAX_ATTEMPTS)
          .isNotNull()
          .isAfter(now);
    }
  }
}
