package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.RefreshTokenRepository;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Stale-PENDING-order expiry contract: {@link OrderService#expireStaleOrder}
 * cancels orders older than the cutoff and releases their listings, without
 * touching orders that are fresh, paid, or already terminal.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link OrderCancelTest}: each test owns its data via unique users and
 * wipes the touched tables in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderExpiryTest {

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

  @Autowired
  private OrderService orderService;

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

  private Item newItem(User seller, ItemStatus status) {
    Item item = new Item("Film camera", "Canon AE-1", 88000L, seller.getId());
    item.setStatus(status);
    return items.save(item);
  }

  private long createOrder(User buyer, Item item) throws Exception {
    String body = mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", "key-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + "}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return ((Number) JsonPath.read(body, "$.data.id")).longValue();
  }

  private void assertItemStatusInListView(User viewer, String keyword, String expectedStatus) throws Exception {
    mockMvc.perform(get("/api/items").param("q", keyword)
            .header("Authorization", "Bearer " + token(viewer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].status").value(expectedStatus));
  }

  @Test
  void staleOrder_expiredAndListingReleasedToListView() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);
    assertItemStatusInListView(buyer, "Film camera", "RESERVED");

    // Cutoff in the future: the just-created order counts as stale.
    boolean expired = orderService.expireStaleOrder(orderId, Instant.now().plusSeconds(3600));

    assertThat(expired).isTrue();
    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELLED));
    assertThat(items.findById(item.getId())).hasValueSatisfying(i ->
        assertThat(i.getStatus()).isEqualTo(ItemStatus.AVAILABLE));
    assertItemStatusInListView(buyer, "Film camera", "AVAILABLE");
    // The active-order guard released: the buyer may order the item again.
    long secondOrderId = createOrder(buyer, item);
    assertThat(secondOrderId).isNotEqualTo(orderId);
  }

  @Test
  void freshOrder_notTouchedByPastCutoff() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);

    // Cutoff in the past: the just-created order is not stale.
    boolean expired = orderService.expireStaleOrder(orderId, Instant.now().minusSeconds(3600));

    assertThat(expired).isFalse();
    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING));
    assertThat(items.findById(item.getId())).hasValueSatisfying(i ->
        assertThat(i.getStatus()).isEqualTo(ItemStatus.RESERVED));
  }

  @Test
  void paidOrder_notExpiredEvenPastTtl() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);
    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk());

    boolean expired = orderService.expireStaleOrder(orderId, Instant.now().plusSeconds(3600));

    assertThat(expired).isFalse();
    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID));
    assertThat(items.findById(item.getId())).hasValueSatisfying(i ->
        assertThat(i.getStatus()).isEqualTo(ItemStatus.RESERVED));
  }

  @Test
  void listingMarkedSoldByHand_listingLeftAlone_orderStillCancelled() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);
    // The seller hand-marked the listing SOLD (PATCH /api/items/{id}/status)
    // while the order was still PENDING. Re-load first: the fixture's
    // detached instance still carries the pre-order @Version, so saving it
    // directly would fail the optimistic-lock check for the wrong reason.
    Item listed = items.findById(item.getId()).orElseThrow();
    listed.setStatus(ItemStatus.SOLD);
    items.save(listed);

    boolean expired = orderService.expireStaleOrder(orderId, Instant.now().plusSeconds(3600));

    assertThat(expired).isTrue();
    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELLED));
    // The listing is NOT flipped back to AVAILABLE: it was never the
    // expiry's reservation to release.
    assertThat(items.findById(item.getId())).hasValueSatisfying(i ->
        assertThat(i.getStatus()).isEqualTo(ItemStatus.SOLD));
  }

  @Test
  void payVsExpiryRace_exactlyOneTransitionWins_no5xx() throws Exception {
    // Three fresh order/item pairs: whichever of the two threads commits
    // second loses its @Version check (or its status re-check), and the
    // final state must be one of the two consistent pairs — never a 5xx
    // and never a half-written transition.
    for (int round = 0; round < 3; round++) {
      User seller = newUser("seller");
      User buyer = newUser("buyer");
      Item item = newItem(seller, ItemStatus.AVAILABLE);
      long orderId = createOrder(buyer, item);

      ExecutorService pool = Executors.newFixedThreadPool(2);
      CountDownLatch gun = new CountDownLatch(1);
      AtomicInteger payHttpStatus = new AtomicInteger();
      AtomicReference<Throwable> expiryFailure = new AtomicReference<>();

      pool.submit(() -> {
        try {
          gun.await();
          // A real client pays through the HTTP endpoint.
          payHttpStatus.set(mockMvc.perform(post("/api/orders/{id}/pay", orderId)
                  .header("Authorization", "Bearer " + token(buyer)))
              .andReturn().getResponse().getStatus());
        } catch (Throwable t) {
          expiryFailure.compareAndSet(null, t);
        }
        return null;
      });
      pool.submit(() -> {
        try {
          gun.await();
          // The expiry job races it: the order is stale (future cutoff).
          orderService.expireStaleOrder(orderId, Instant.now().plusSeconds(3600));
        } catch (ObjectOptimisticLockingFailureException e) {
          // Expected when pay commits mid-transaction: the pay won, the
          // row is skipped. The scheduled job catches this per row; the
          // direct call propagates it.
        } catch (Throwable t) {
          expiryFailure.compareAndSet(null, t);
        }
        return null;
      });

      gun.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

      assertThat(expiryFailure.get())
          .as("round %d: no unexpected failure in either racer", round)
          .isNull();
      // Pay either won (200), lost to an already-cancelled order (422), or
      // lost the version race (409) — never a 5xx.
      assertThat(payHttpStatus.get())
          .as("round %d: pay answers 200/422/409, never 5xx", round)
          .isIn(200, 409, 422);

      OrderStatus finalOrder = orders.findById(orderId).orElseThrow().getStatus();
      ItemStatus finalItem = items.findById(item.getId()).orElseThrow().getStatus();
      assertThat(finalOrder + "/" + finalItem)
          .as("round %d: exactly one transition won, state is consistent", round)
          .isIn("PAID/RESERVED", "CANCELLED/AVAILABLE");
    }
  }
}
