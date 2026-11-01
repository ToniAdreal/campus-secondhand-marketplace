package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.dispute.OrderDisputeRepository;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.order.IdempotencyKeyRepository;
import com.toni.marketplace.order.OrderRepository;
import com.toni.marketplace.order.PaymentAttemptRepository;
import com.toni.marketplace.order.SellerPayoutRepository;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Rate-limit behavior of the order-tail POSTs through
 * {@code AuthRateLimitFilter} (backlog #289): {@code POST
 * /api/orders/{id}/confirm-receipt} and {@code POST
 * /api/orders/{id}/handover-code/regenerate} share one per-<em>user</em>
 * bucket (production default 20/minute — pinned to 3/minute here so the
 * burst arithmetic stays small), keyed on the JWT principal id like the
 * message/report buckets, with the per-IP fallback for a missing/invalid
 * token. The bucket never resets on success — an idempotent re-confirm
 * must not refill the allowance — and it is deliberately separate from
 * the #228 transition bucket, so transition traffic can never starve
 * receipt/regenerate traffic, nor vice versa.
 *
 * <p>Buckets are in-memory and survive across tests within this class, so
 * each test uses fresh users (per-user keys), mirroring
 * {@code OrderTransitionItemMutationRateLimitTest}.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring that class:
 * order creation reserves its idempotency key in a {@code REQUIRES_NEW}
 * transaction whose foreign keys can only see rows that are already
 * committed. The test owns its data via its unique usernames and wipes
 * every table it touches in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the order-tail surfaces, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.order-receipt-attempts-per-minute=3",
    // Pin the #228 transition bucket to the same small size so the
    // reverse-independence test can exhaust it without 20 pays.
    "app.auth.rate-limit.order-transition-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the
    // bucket assertions.
    "app.auth.login-lockout.enabled=false"})
class OrderReceiptRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;
  @Autowired private RefreshTokenRepository refreshTokens;
  @Autowired private ItemRepository items;
  @Autowired private OrderRepository orders;
  @Autowired private IdempotencyKeyRepository idempotencyKeys;
  @Autowired private NotificationRepository notifications;
  @Autowired private PaymentAttemptRepository paymentAttempts;
  @Autowired private SellerPayoutRepository payouts;
  @Autowired private AuditLogRepository auditLog;
  @Autowired private OrderDisputeRepository disputes;

  @AfterEach
  void cleanUp() {
    // FK-safe order: payout, payment-attempt, dispute and idempotency
    // rows first (their order FKs point at orders), then notifications
    // and audit rows, then orders, refresh tokens, items, users.
    payouts.deleteAll();
    paymentAttempts.deleteAll();
    disputes.deleteAll();
    idempotencyKeys.deleteAll();
    notifications.deleteAll();
    auditLog.deleteAll();
    orders.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User freshUser() {
    String tag = "receipt-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private String bearer(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private long createListingOnce(String accessToken) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"ThinkPad T480s\",\"description\":\"good battery\","
                + "\"priceCents\":25000}"))
        .andExpect(status().isOk())
        .andReturn();
    return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.id"))
        .longValue();
  }

  private long createOrderOnce(String accessToken, long itemId) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + accessToken)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + itemId + "}"))
        .andExpect(status().isOk())
        .andReturn();
    return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.id"))
        .longValue();
  }

  private void payOnce(String accessToken, long orderId) throws Exception {
    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PAID"));
  }

  private void shipOnce(String accessToken, long orderId) throws Exception {
    mockMvc.perform(post("/api/orders/{id}/ship", orderId)
            .header("Authorization", "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"carrier\":\"SF Express\",\"trackingNumber\":\"SF123456789\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("SHIPPED"));
  }

  private ResultActions confirmOnce(String accessToken, long orderId) throws Exception {
    return mockMvc.perform(post("/api/orders/{id}/confirm-receipt", orderId)
        .header("Authorization", "Bearer " + accessToken));
  }

  private ResultActions regenerateOnce(String accessToken, long orderId) throws Exception {
    return mockMvc.perform(post("/api/orders/{id}/handover-code/regenerate", orderId)
        .header("Authorization", "Bearer " + accessToken));
  }

  @Test
  void confirmBurstUnderTheLimitPassesIdempotently() throws Exception {
    User seller = freshUser();
    User buyer = freshUser();
    long orderId = createOrderOnce(bearer(buyer), createListingOnce(bearer(seller)));
    payOnce(bearer(buyer), orderId);
    shipOnce(bearer(seller), orderId);
    String buyerToken = bearer(buyer);
    // First confirm completes the order; the next two are idempotent
    // re-confirms, not 429s — the bucket counts attempts, but under the
    // limit every attempt passes.
    confirmOnce(buyerToken, orderId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    confirmOnce(buyerToken, orderId).andExpect(status().isOk());
    confirmOnce(buyerToken, orderId).andExpect(status().isOk());
  }

  @Test
  void fourthReceiptAttemptIsRejectedWith429Envelope() throws Exception {
    User seller = freshUser();
    User buyer = freshUser();
    long orderId = createOrderOnce(bearer(buyer), createListingOnce(bearer(seller)));
    payOnce(bearer(buyer), orderId);
    shipOnce(bearer(seller), orderId);
    String buyerToken = bearer(buyer);
    confirmOnce(buyerToken, orderId).andExpect(status().isOk());
    confirmOnce(buyerToken, orderId).andExpect(status().isOk());
    confirmOnce(buyerToken, orderId).andExpect(status().isOk());
    confirmOnce(buyerToken, orderId)
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"))
        .andExpect(header -> {
          String retryAfter = header.getResponse().getHeader("Retry-After");
          if (retryAfter == null) {
            throw new AssertionError("missing Retry-After header on the 429");
          }
        });
  }

  @Test
  void regenerateBurstUnderTheLimitPasses() throws Exception {
    User seller = freshUser();
    User buyer = freshUser();
    long orderId = createOrderOnce(bearer(buyer), createListingOnce(bearer(seller)));
    payOnce(bearer(buyer), orderId);
    String buyerToken = bearer(buyer);
    // Each regenerate mints a fresh code, so every call under the limit
    // is a genuine 200 carrying a new plaintext.
    regenerateOnce(buyerToken, orderId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.handoverCode").exists());
    regenerateOnce(buyerToken, orderId).andExpect(status().isOk());
    regenerateOnce(buyerToken, orderId).andExpect(status().isOk());
  }

  @Test
  void fourthRegenerateIsRejectedWith429Envelope() throws Exception {
    User seller = freshUser();
    User buyer = freshUser();
    long orderId = createOrderOnce(bearer(buyer), createListingOnce(bearer(seller)));
    payOnce(bearer(buyer), orderId);
    String buyerToken = bearer(buyer);
    regenerateOnce(buyerToken, orderId).andExpect(status().isOk());
    regenerateOnce(buyerToken, orderId).andExpect(status().isOk());
    regenerateOnce(buyerToken, orderId).andExpect(status().isOk());
    regenerateOnce(buyerToken, orderId)
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }

  @Test
  void receiptBucketIsSharedAcrossEndpoints() throws Exception {
    // Exhausting the bucket with regenerates leaves a confirm-receipt
    // throttled too — one bucket for the whole order tail, not one per
    // endpoint.
    User seller = freshUser();
    User buyer = freshUser();
    String sellerToken = bearer(seller);
    String buyerToken = bearer(buyer);
    long paidOrder = createOrderOnce(buyerToken, createListingOnce(sellerToken));
    payOnce(buyerToken, paidOrder);
    long shippedOrder = createOrderOnce(buyerToken, createListingOnce(sellerToken));
    payOnce(buyerToken, shippedOrder);
    shipOnce(sellerToken, shippedOrder);
    regenerateOnce(buyerToken, paidOrder).andExpect(status().isOk());
    regenerateOnce(buyerToken, paidOrder).andExpect(status().isOk());
    regenerateOnce(buyerToken, paidOrder).andExpect(status().isOk());
    confirmOnce(buyerToken, shippedOrder).andExpect(status().isTooManyRequests());
  }

  @Test
  void receiptBucketIsPerUser() throws Exception {
    // Exhausting one buyer's receipt bucket leaves another buyer's
    // untouched.
    User seller = freshUser();
    User first = freshUser();
    User second = freshUser();
    String sellerToken = bearer(seller);
    String firstToken = bearer(first);
    long firstOrder = createOrderOnce(firstToken, createListingOnce(sellerToken));
    payOnce(firstToken, firstOrder);
    regenerateOnce(firstToken, firstOrder).andExpect(status().isOk());
    regenerateOnce(firstToken, firstOrder).andExpect(status().isOk());
    regenerateOnce(firstToken, firstOrder).andExpect(status().isOk());
    regenerateOnce(firstToken, firstOrder).andExpect(status().isTooManyRequests());

    long secondOrder = createOrderOnce(bearer(second), createListingOnce(sellerToken));
    payOnce(bearer(second), secondOrder);
    regenerateOnce(bearer(second), secondOrder).andExpect(status().isOk());
  }

  @Test
  void receiptBucketIsIndependentOfTransitionBucket() throws Exception {
    // Exhausting the receipt bucket leaves the #228 transition bucket
    // untouched; the reverse direction is covered by
    // transitionBucketExhaustionLeavesReceiptBucketIntact below.
    User seller = freshUser();
    User buyer = freshUser();
    String sellerToken = bearer(seller);
    String buyerToken = bearer(buyer);
    long paidOrder = createOrderOnce(buyerToken, createListingOnce(sellerToken));
    payOnce(buyerToken, paidOrder);
    regenerateOnce(buyerToken, paidOrder).andExpect(status().isOk());
    regenerateOnce(buyerToken, paidOrder).andExpect(status().isOk());
    regenerateOnce(buyerToken, paidOrder).andExpect(status().isOk());
    regenerateOnce(buyerToken, paidOrder).andExpect(status().isTooManyRequests());
    // A fresh PENDING order still pays: the transition bucket is intact.
    long freshOrder = createOrderOnce(buyerToken, createListingOnce(sellerToken));
    payOnce(buyerToken, freshOrder);
  }

  @Test
  void transitionBucketExhaustionLeavesReceiptBucketIntact() throws Exception {
    // Reverse direction of the previous test: exhausting the #228
    // transition bucket must not starve receipt/regenerate traffic.
    User seller = freshUser();
    User buyer = freshUser();
    String sellerToken = bearer(seller);
    String buyerToken = bearer(buyer);
    long paidOrder = createOrderOnce(buyerToken, createListingOnce(sellerToken));
    payOnce(buyerToken, paidOrder);
    payOnce(buyerToken, createOrderOnce(buyerToken, createListingOnce(sellerToken)));
    payOnce(buyerToken, createOrderOnce(buyerToken, createListingOnce(sellerToken)));
    // Fourth pay hits the pinned 3/min transition ceiling.
    long fourthOrder = createOrderOnce(buyerToken, createListingOnce(sellerToken));
    mockMvc.perform(post("/api/orders/{id}/pay", fourthOrder)
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
    // The receipt bucket is untouched: regenerate still succeeds.
    regenerateOnce(buyerToken, paidOrder).andExpect(status().isOk());
  }

  @Test
  void anonymousReceiptFallbackStillThrottles() throws Exception {
    // No token: the filter falls back to a per-IP bucket. The first three
    // anonymous confirms are answered 401 by the security chain (the
    // bucket still consumed each attempt); the fourth is short-circuited
    // 429 by the filter itself. A dedicated remote address keeps this
    // test's IP bucket away from every other test's.
    String ip = "203.0.113.43";
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(post("/api/orders/{id}/confirm-receipt", 1L)
              .with(request -> {
                request.setRemoteAddr(ip);
                return request;
              }))
          .andExpect(status().isUnauthorized());
    }
    mockMvc.perform(post("/api/orders/{id}/confirm-receipt", 1L)
            .with(request -> {
              request.setRemoteAddr(ip);
              return request;
            }))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }
}
