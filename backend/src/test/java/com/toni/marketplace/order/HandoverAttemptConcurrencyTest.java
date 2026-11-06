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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Backlog #337 — the handover failed-attempt counter under a
 * concurrent burst of wrong guesses. {@code recordFailure} used to be
 * a lock-free read-modify-write on a {@code @Version} row: measured
 * before the fix, 8 simultaneous failures recorded only 1–3 attempts
 * and the rest died with
 * {@code ObjectOptimisticLockingFailureException} (which the HTTP
 * layer would have answered 409 instead of the identical 400), and
 * the 5-attempt lock never engaged. The row is now read under a
 * pessimistic write lock, so writers serialise: every increment
 * lands and the lock engages at the ceiling.
 *
 * <p>This class pins the fix on H2, where it runs in the default
 * unit-test phase. {@link HandoverAttemptRaceMySqlIT} pins the same
 * invariant on real MySQL 8 in CI — H2's row locking is not InnoDB's,
 * so the MySQL leg is the authoritative one.
 */
@SpringBootTest
class HandoverAttemptConcurrencyTest {

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
          f.get(60, TimeUnit.SECONDS);
        }
      } finally {
        pool.shutdownNow();
      }

      // No lost increments, no optimistic-lock escape: every one of
      // the 8 failures counted, so the 5-attempt lock is engaged.
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
