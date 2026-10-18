package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;

import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Concurrent detail reads (backlog #186): two threads read the same
 * listing's detail at the same moment as the same buyer. The contract
 * is timing-invariant: the atomic
 * {@code UPDATE … SET view_count = view_count + 1} lands exactly one
 * increment per read (final stored count exactly 2), the two responses
 * carry the counts 1 and 2 in some order, and neither read fails —
 * in particular no optimistic-lock 409 ever leaks to a reader from
 * counting (the failure mode a read-modify-write on the entity would
 * have produced).
 *
 * <p>Deliberately NOT {@code @Transactional}: each race thread must
 * run the service in its own committed transaction, like two real
 * requests. Seeded rows are cleaned up in {@link #cleanUp()}.
 */
@SpringBootTest
class ItemViewCountRaceTest {

  @Autowired
  private ItemService itemService;

  @Autowired
  private ItemRepository items;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  private Long buyerId;
  private Long itemId;

  @BeforeEach
  void seed() {
    User seller = users.save(new User("race-seller", "race-seller@example.com",
        passwords.encode("seller-password")));
    User buyer = users.save(new User("race-buyer", "race-buyer@example.com",
        passwords.encode("buyer-password")));
    buyerId = buyer.getId();
    itemId = items.save(new Item("Race bike", "rides fine", 12000L, seller.getId())).getId();
  }

  @AfterEach
  void cleanUp() {
    items.deleteAll();
    users.deleteAll();
  }

  @Test
  void twoConcurrentBuyerDetailReadsLandExactlyTwoIncrements() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Long>> futures = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        futures.add(pool.submit(() -> {
          start.await();
          return itemService.getItemView(itemId, buyerId, false).dto().viewCount();
        }));
      }
      start.countDown();
      List<Long> returnedCounts = new ArrayList<>();
      for (Future<Long> future : futures) {
        returnedCounts.add(future.get(60, TimeUnit.SECONDS));
      }
      assertThat(returnedCounts).containsExactlyInAnyOrder(1L, 2L);
    } finally {
      pool.shutdownNow();
    }
    assertThat(items.findById(itemId).orElseThrow().getViewCount()).isEqualTo(2);
  }
}
