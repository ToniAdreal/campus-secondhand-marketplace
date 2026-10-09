package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Backlog #93 — order/payment Micrometer counters, mirroring
 * {@code AuthSecurityMetricsTest} (#79). Each test measures deltas on the
 * shared {@link MeterRegistry} (absolute values would couple tests to
 * execution order), and pins the core rule: counters count transitions,
 * not calls — every idempotent replay (same Idempotency-Key, re-pay,
 * re-cancel, re-complete, re-refund, re-expire) moves nothing.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link OrderPaymentTest}: each test owns its data via unique users and
 * wipes the touched tables in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    // Same opt-in as ActuatorSecurityTest: @AutoConfigureMockMvc disables
    // metrics export by default, so /actuator/prometheus would 404 here.
    "management.prometheus.metrics.export.enabled=true"
})
class OrderPaymentMetricsTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private MeterRegistry registry;

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

  private double count(String name) {
    Counter counter = registry.find(name).counter();
    return counter == null ? 0.0 : counter.count();
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

  private long createOrder(User buyer, Item item, String key) throws Exception {
    String body = mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + "}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return ((Number) JsonPath.read(body, "$.data.id")).longValue();
  }

  private long createOrder(User buyer, Item item) throws Exception {
    return createOrder(buyer, item, "key-" + UUID.randomUUID());
  }

  private void act(User actor, long orderId, String action) throws Exception {
    mockMvc.perform(post("/api/orders/{id}/" + action, orderId)
            .header("Authorization", "Bearer " + token(actor)))
        .andExpect(status().isOk());
  }

  @Test
  void createdAndPaidCounters_countTransitionsNotReplays() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    String key = "key-" + UUID.randomUUID();
    double createdBefore = count("orders.created");
    double paidBefore = count("orders.paid");

    long orderId = createOrder(buyer, item, key);
    // Idempotency-Key replay returns the original order: no new creation.
    assertThat(createOrder(buyer, item, key)).isEqualTo(orderId);
    assertThat(count("orders.created")).isEqualTo(createdBefore + 1);

    act(buyer, orderId, "pay");
    // Re-pay of an already-PAID order is an idempotent no-op.
    act(buyer, orderId, "pay");
    assertThat(count("orders.paid")).isEqualTo(paidBefore + 1);
  }

  @Test
  void cancelledCounter_reCancelMovesNothing() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    long orderId = createOrder(buyer, newItem(seller));
    double before = count("orders.cancelled");

    act(buyer, orderId, "cancel");
    act(buyer, orderId, "cancel");

    assertThat(count("orders.cancelled")).isEqualTo(before + 1);
  }

  @Test
  void completedAndRefundedCounters_repeatsMoveNothing() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    long completedId = createOrder(buyer, newItem(seller));
    long refundedId = createOrder(buyer, newItem(seller));
    act(buyer, completedId, "pay");
    act(buyer, refundedId, "pay");
    double completedBefore = count("orders.completed");
    double refundedBefore = count("orders.refunded");

    act(seller, completedId, "complete");
    act(seller, completedId, "complete");
    act(seller, refundedId, "refund");
    act(seller, refundedId, "refund");

    assertThat(count("orders.completed")).isEqualTo(completedBefore + 1);
    assertThat(count("orders.refunded")).isEqualTo(refundedBefore + 1);
  }

  @Test
  void expiredCounter_countsExpiryNotBuyerCancel() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    long orderId = createOrder(buyer, newItem(seller));
    double expiredBefore = count("orders.expired");
    double cancelledBefore = count("orders.cancelled");

    // Future cutoff: the just-created order counts as stale (see
    // OrderExpiryTest). The second call finds it non-PENDING: no-op.
    assertThat(orderService.expireStaleOrder(orderId, Instant.now().plusSeconds(3600))).isTrue();
    assertThat(orderService.expireStaleOrder(orderId, Instant.now().plusSeconds(3600))).isFalse();

    assertThat(count("orders.expired")).isEqualTo(expiredBefore + 1);
    // Expiry is a system transition — it must not inflate the buyer
    // cancel counter.
    assertThat(count("orders.cancelled")).isEqualTo(cancelledBefore);
  }

  @Test
  void countersAreScrapeableViaPrometheusBehindAuth() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    createOrder(buyer, newItem(seller));

    mockMvc.perform(get("/actuator/prometheus"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/actuator/prometheus")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("orders_created")));
  }
}
