package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.RefreshTokenRepository;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * POST /api/orders reserves the listing as part of creation.
 *
 * <p>Before this change, order creation never touched {@code Item.status}:
 * the order row existed but the listing stayed AVAILABLE, so the list view
 * kept offering an already-ordered item (the {@code uq_order_active_item}
 * guard blocked a second order, but the UX lied). The listing now flips to
 * RESERVED inside creation, and the {@code Item.@Version} bump makes
 * concurrent creations fail fast with 409 instead of both inserting.
 *
 * <p>Same non-transactional test shape as {@link OrderIdempotencyTest}: the
 * idempotency-key lifecycle commits in its own transactions, so each test
 * owns its rows via unique usernames and wipes the tables in cleanUp().
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderCreateItemStatusTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository items;

  @Autowired
  private OrderRepository orders;

  @Autowired
  private IdempotencyKeyRepository keys;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @AfterEach
  void cleanUp() {
    keys.deleteAll();
    orders.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User newUser(String role) {
    String tag = role + "-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private Item newItem(User seller) {
    Item item = new Item("Film camera", "Canon AE-1", 88000L, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    return items.save(item);
  }

  private String orderBody(long itemId) {
    return "{\"itemId\":" + itemId + "}";
  }

  private String key() {
    return "key-" + UUID.randomUUID();
  }

  @Test
  void creatingOrderFlipsItemToReserved() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);

    mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", key())
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(item.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.status").value("PENDING"));

    assertThat(orders.findAll()).hasSize(1);
    // The regression: the listing must no longer read as buyable.
    assertThat(items.findById(item.getId())).hasValueSatisfying(i ->
        assertThat(i.getStatus()).isEqualTo(ItemStatus.RESERVED));
  }

  @Test
  void failedCreateLeavesItemAvailable() throws Exception {
    User seller = newUser("seller");
    Item item = newItem(seller);

    // Buying your own listing is rejected before any state change happens.
    mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(seller))
            .header("Idempotency-Key", key())
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(item.getId())))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));

    assertThat(orders.findAll()).isEmpty();
    assertThat(items.findById(item.getId())).hasValueSatisfying(i ->
        assertThat(i.getStatus()).isEqualTo(ItemStatus.AVAILABLE));
  }

  @Test
  void concurrentCreatesOnOneItem_exactlyOneWins_no5xx() throws Exception {
    User seller = newUser("seller");
    Item item = newItem(seller);

    int threads = 4;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger ok = new AtomicInteger();
    AtomicInteger conflict = new AtomicInteger();
    AtomicInteger serverError = new AtomicInteger();
    try {
      for (int i = 0; i < threads; i++) {
        // Distinct buyers and keys: every thread is a genuinely independent
        // purchase attempt, so exactly one must win the item.
        User buyer = newUser("racer");
        String buyerToken = token(buyer);
        String idemKey = key();
        pool.submit(() -> {
          try {
            start.await();
            int code = mockMvc.perform(post("/api/orders")
                    .header("Authorization", "Bearer " + buyerToken)
                    .header("Idempotency-Key", idemKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(orderBody(item.getId())))
                .andReturn().getResponse().getStatus();
            if (code == 200) {
              ok.incrementAndGet();
            } else if (code == 409) {
              conflict.incrementAndGet();
            } else {
              serverError.incrementAndGet();
            }
          } catch (Exception e) {
            serverError.incrementAndGet();
          }
          return null;
        });
      }
      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    // Exactly one buyer wins; every loser gets a clean 409 (status check,
    // uq_order_active_item, or the @Version fail-fast) — never a 500, never
    // a second order row, and the listing ends up RESERVED.
    assertThat(serverError.get()).isZero();
    assertThat(ok.get()).isEqualTo(1);
    assertThat(conflict.get()).isEqualTo(threads - 1);
    assertThat(orders.findAll()).hasSize(1);
    assertThat(items.findById(item.getId())).hasValueSatisfying(i ->
        assertThat(i.getStatus()).isEqualTo(ItemStatus.RESERVED));
  }
}
