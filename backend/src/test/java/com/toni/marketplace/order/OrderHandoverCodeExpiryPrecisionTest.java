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
import java.time.Instant;
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
 * Backlog #345: the exact-TTL boundary case from
 * {@link OrderHandoverCodeExpiryTest} with a clock start whose
 * nanosecond fraction rounds UP at the stored precision. Proven
 * root cause: the {@code handover_code_expires_at} column is
 * {@code TIMESTAMP} with second precision on both dialects, and
 * the round trip rounds to the nearest second — a mint at
 * 12:00:00.999999999 with a 1-hour TTL stored 13:00:01, so
 * advancing exactly 1h left now BEFORE the stored expiry and
 * the complete call wrongly answered 200 instead of 422.
 * The fix truncates the minted expiry to whole seconds, so the
 * stored value never exceeds the intended expiry. These tests
 * seed that adversarial fraction deterministically; the
 * unseeded {@link OrderHandoverCodeExpiryTest} covers the
 * random-fraction case on every run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "app.orders.handover.ttl=1h"
})
class OrderHandoverCodeExpiryPrecisionTest {

  @TestConfiguration
  static class SeededClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(Instant.parse("2026-03-01T12:00:00.999999999Z"));
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

  @Test
  void adversarialFractionCodeIsDeadAtExactlyTtl() throws Exception {
    Instant mintInstant = clock.instant();
    System.out.println("REPRO mintInstant=" + mintInstant);
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = new Item("Film camera", "Canon AE-1", 88000L, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    item = items.save(item);

    String orderBody = mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", "key-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + "}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    long orderId = ((Number) JsonPath.read(orderBody, "$.data.id")).longValue();

    String payBody = mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.handoverCode").isString())
        .andReturn().getResponse().getContentAsString();
    String code = JsonPath.read(payBody, "$.data.handoverCode");

    Instant storedExpiry = orders.findById(orderId).orElseThrow().getHandoverCodeExpiresAt();
    Instant expectedExpiry = mintInstant.plus(Duration.ofHours(1));
    System.out.println("REPRO expectedExpiry=" + expectedExpiry
        + " storedExpiry=" + storedExpiry
        + " storedAfterExpected=" + (storedExpiry != null && storedExpiry.isAfter(expectedExpiry))
        + " clockNow=" + clock.instant());

    clock.advance(Duration.ofHours(1));
    MvcResult result = mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"handoverCode\":\"" + code + "\"}"))
        .andReturn();
    System.out.println("REPRO completeStatus=" + result.getResponse().getStatus());
    assertThat(result.getResponse().getStatus()).isEqualTo(422);
    assertThat(orders.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.PAID);
  }

  @Test
  void adversarialFractionStoredExpiryNeverExceedsIntendedExpiry() throws Exception {
    Instant mintInstant = clock.instant();
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = new Item("Film camera", "Canon AE-1", 88000L, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    item = items.save(item);
    String orderBody = mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", "key-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + "}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    long orderId = ((Number) JsonPath.read(orderBody, "$.data.id")).longValue();
    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk());

    Instant storedExpiry = orders.findById(orderId).orElseThrow().getHandoverCodeExpiresAt();
    Instant intendedExpiry = mintInstant.plus(Duration.ofHours(1));
    assertThat(storedExpiry).isNotNull();
    assertThat(storedExpiry.isAfter(intendedExpiry)).isFalse();
    assertThat(storedExpiry)
        .isEqualTo(intendedExpiry.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
  }

  @Test
  void adversarialFractionCodeStillCompletesJustBeforeStoredExpiry() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = new Item("Film camera", "Canon AE-1", 88000L, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    item = items.save(item);
    String orderBody = mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", "key-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + "}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    long orderId = ((Number) JsonPath.read(orderBody, "$.data.id")).longValue();
    String payBody = mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.handoverCode").isString())
        .andReturn().getResponse().getContentAsString();
    String code = JsonPath.read(payBody, "$.data.handoverCode");

    // 59m59s after a .999999999 mint is 1ns before the stored
    // second-aligned expiry: truncation must not kill the code
    // early by more than a second.
    clock.advance(Duration.ofMinutes(59).plusSeconds(59));
    MvcResult result = mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"handoverCode\":\"" + code + "\"}"))
        .andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(orders.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.COMPLETED);
  }
}
