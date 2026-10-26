package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.MutableClock;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.RefreshTokenRepository;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Backlog #234 — the handover code expires. Pay mints the code with
 * an expiry of now + {@code app.orders.handover.ttl} (7 days in
 * production; 1 hour here so the tests can cross it), and the
 * complete exchange refuses an expired code with 422 naming the
 * regenerate endpoint instead of the 400 a wrong code gets.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link OrderHandoverCodeTest}: each test owns its data via unique
 * users and wipes the touched tables in {@code cleanUp()}. The
 * application {@code Clock} bean is replaced with a
 * {@link MutableClock} so mint/expiry instants are deterministic.
 * Tokens are minted fresh at each call site — the access TTL is 15
 * minutes on the same clock, so a token minted before a 61-minute
 * advance would already be dead at the complete call.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "app.orders.handover.ttl=1h"
})
class OrderHandoverCodeExpiryTest {

  @TestConfiguration
  static class ControllableClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private MutableClock clock;

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
  private AuditLogRepository auditLog;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @AfterEach
  void cleanUp() {
    auditLog.deleteAll();
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

  /** Pays as the buyer; returns the handover code disclosed in the pay response. */
  private String payOrder(User buyer, long orderId) throws Exception {
    String body = mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.handoverCode").isString())
        .andReturn().getResponse().getContentAsString();
    String code = JsonPath.read(body, "$.data.handoverCode");
    assertThat(code).matches("\\d{6}");
    return code;
  }

  private MvcResult complete(User caller, long orderId, String code) throws Exception {
    return mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(caller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"handoverCode\":\"" + code + "\"}"))
        .andReturn();
  }

  private String regenerate(User buyer, long orderId) throws Exception {
    String body = mockMvc.perform(post("/api/orders/{id}/handover-code/regenerate", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return JsonPath.read(body, "$.data.handoverCode");
  }

  @Test
  void freshCodeCompletesBeforeExpiry() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);
    String code = payOrder(buyer, orderId);

    // Mint stamps an expiry (now + ttl), never a null one.
    assertThat(orders.findById(orderId).orElseThrow().getHandoverCodeExpiresAt()).isNotNull();

    clock.advance(Duration.ofMinutes(59));
    MvcResult result = complete(seller, orderId, code);
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(orders.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.COMPLETED);
  }

  @Test
  void expiredCodeAnswers422NamingRegenerate_orderStaysPaid_noFailureCounted()
      throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);
    String code = payOrder(buyer, orderId);

    clock.advance(Duration.ofMinutes(61));
    MvcResult result = complete(seller, orderId, code);
    assertThat(result.getResponse().getStatus()).isEqualTo(422);
    String body = result.getResponse().getContentAsString();
    assertThat(JsonPath.<String>read(body, "$.message")).contains("regenerate");
    assertThat(JsonPath.<String>read(body, "$.message"))
        .contains("/handover-code/regenerate");

    // The order is untouched: still PAID, and presenting an expired
    // code is a state, not a guess — it feeds no failure counter.
    Order stored = orders.findById(orderId).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(OrderStatus.PAID);
    assertThat(stored.getHandoverFailedAttempts()).isEqualTo(0);
  }

  @Test
  void regenerateAfterExpirySucceedsAndFreshCodeCompletes() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);
    String oldCode = payOrder(buyer, orderId);

    clock.advance(Duration.ofMinutes(61));
    // The old code is dead, but regeneration — the recovery path the
    // 422 points at — still works while the order is PAID.
    String freshCode = regenerate(buyer, orderId);
    assertThat(freshCode).matches("\\d{6}");
    assertThat(freshCode).isNotEqualTo(oldCode);

    // The regenerated code carries a fresh lifetime: it completes
    // even though the clock is past the original code's expiry.
    MvcResult result = complete(seller, orderId, freshCode);
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(orders.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.COMPLETED);
  }

  @Test
  void codeExpiredAtExactlyTtlIsAlreadyDead() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);
    String code = payOrder(buyer, orderId);

    // Boundary pinned: at exactly now + ttl the code is expired —
    // the TTL is a lifetime, not a grace period past it.
    clock.advance(Duration.ofHours(1));
    MvcResult result = complete(seller, orderId, code);
    assertThat(result.getResponse().getStatus()).isEqualTo(422);
    assertThat(orders.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.PAID);
  }

  @Test
  void pre234CodeWithNullExpiryIsGrandfathered() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);
    String code = payOrder(buyer, orderId);

    // Simulate a row minted before #234 landed: a hash, no expiry.
    Order stored = orders.findById(orderId).orElseThrow();
    stored.setHandoverCodeExpiresAt(null);
    orders.save(stored);

    // Far past any TTL: the grandfathered code still completes, so
    // this change cannot strand an in-flight handover issued before it.
    clock.advance(Duration.ofDays(30));
    MvcResult result = complete(seller, orderId, code);
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(orders.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.COMPLETED);
  }
}
