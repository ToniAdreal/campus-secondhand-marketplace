package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.dispute.DisputeEvidenceRepository;
import com.toni.marketplace.dispute.OrderDisputeRepository;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.moderation.ListingReportRepository;
import com.toni.marketplace.moderation.ReportAppealRepository;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.offer.PriceOfferRepository;
import com.toni.marketplace.order.IdempotencyKeyRepository;
import com.toni.marketplace.order.OrderRepository;
import com.toni.marketplace.order.PaymentAttemptRepository;
import com.toni.marketplace.review.SellerReviewRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Rate-limit behavior of the social / money-adjacent creation writes
 * through {@code AuthRateLimitFilter} (backlog #282): {@code POST
 * /api/items/{id}/offers}, {@code POST /api/orders/{id}/review},
 * {@code POST /api/orders/{id}/dispute}, {@code POST
 * /api/disputes/{id}/evidence} and {@code POST
 * /api/seller/reports/{id}/appeal} share one per-<em>user</em> bucket
 * (production default 20/minute — pinned to 3/minute here so the
 * burst arithmetic stays small), keyed on the JWT principal id like
 * the message/report buckets, with the per-IP fallback for a
 * missing/invalid token. The bucket never resets on success — a
 * filed offer, review, dispute, evidence photo or appeal must not
 * refill the allowance.
 *
 * <p>Buckets are in-memory and survive across tests within this class, so
 * each test uses fresh users (per-user keys), mirroring
 * {@code OrderTransitionItemMutationRateLimitTest}.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@code OrderTransitionItemMutationRateLimitTest}: order creation reserves its
 * idempotency key in a {@code REQUIRES_NEW} transaction whose foreign
 * keys can only see rows that are already committed. The test owns its
 * data via its unique usernames and wipes every table it touches in
 * {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the social-write surfaces, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.social-write-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the
    // bucket assertions.
    "app.auth.login-lockout.enabled=false",
    "app.uploads.dir=target/test-uploads/social-write"})
class SocialWriteRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();
  private static final Path UPLOAD_DIR = Paths.get("target/test-uploads/social-write");

  /** Minimal valid PNG (1x1 pixel) — small enough for the 16 KB test cap. */
  private static final byte[] PNG_BYTES = {
      (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
      0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
      0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
      0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
      (byte) 0x89, 0x00, 0x00, 0x00, 0x0B, 0x49, 0x44, 0x41,
      0x54, 0x78, (byte) 0x9C, 0x63, 0x60, 0x00, 0x02, 0x00,
      0x00, 0x05, 0x00, 0x01, 0x7A, 0x5E, (byte) 0xAB, 0x3F,
      0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
      (byte) 0xAE, 0x42, 0x60, (byte) 0x82};

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
  @Autowired private PriceOfferRepository offers;
  @Autowired private SellerReviewRepository reviews;
  @Autowired private OrderDisputeRepository disputes;
  @Autowired private DisputeEvidenceRepository evidence;
  @Autowired private ListingReportRepository reports;
  @Autowired private ReportAppealRepository appeals;

  @AfterEach
  void cleanUp() throws IOException {
    // FK-safe order: evidence and appeals first (they point at disputes
    // and reports), then disputes, reports and reviews (they point at
    // orders/items), then offers, payment attempts and idempotency rows
    // (they point at orders), then orders, notifications and refresh
    // tokens (all point at users), then items, then users.
    evidence.deleteAll();
    appeals.deleteAll();
    disputes.deleteAll();
    reports.deleteAll();
    reviews.deleteAll();
    offers.deleteAll();
    paymentAttempts.deleteAll();
    idempotencyKeys.deleteAll();
    orders.deleteAll();
    notifications.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
    if (Files.exists(UPLOAD_DIR)) {
      try (Stream<Path> walk = Files.walk(UPLOAD_DIR)) {
        walk.sorted(Comparator.reverseOrder()).forEach(path -> {
          try {
            Files.deleteIfExists(path);
          } catch (IOException ignored) {
            // Best effort: a leftover test upload never fails the suite.
          }
        });
      }
    }
  }

  private User freshUser() {
    String tag = "socialwrite-" + SEQ.incrementAndGet() + "-"
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

  private ResultActions offerOnce(String accessToken, long itemId) throws Exception {
    return mockMvc.perform(post("/api/items/{id}/offers", itemId)
        .header("Authorization", "Bearer " + accessToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"priceCents\":10000}"));
  }

  private ResultActions reviewOnce(String accessToken, long orderId) throws Exception {
    return mockMvc.perform(post("/api/orders/{id}/review", orderId)
        .header("Authorization", "Bearer " + accessToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"rating\":5,\"body\":\"great\"}"));
  }

  private ResultActions disputeOnce(String accessToken, long orderId) throws Exception {
    return mockMvc.perform(post("/api/orders/{id}/dispute", orderId)
        .header("Authorization", "Bearer " + accessToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"reason\":\"item not as described\"}"));
  }

  private ResultActions evidenceOnce(String accessToken, long disputeId) throws Exception {
    return mockMvc.perform(multipart("/api/disputes/{id}/evidence", disputeId)
        .file(new MockMultipartFile("file", "camera.png", "image/png", PNG_BYTES))
        .header("Authorization", "Bearer " + accessToken));
  }

  private ResultActions appealOnce(String accessToken, long reportId) throws Exception {
    return mockMvc.perform(post("/api/seller/reports/{id}/appeal", reportId)
        .header("Authorization", "Bearer " + accessToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"reason\":\"please reconsider\"}"));
  }

  private ResultActions payOnce(String accessToken, long orderId) throws Exception {
    return mockMvc.perform(post("/api/orders/{id}/pay", orderId)
        .header("Authorization", "Bearer " + accessToken));
  }

  @Test
  void offerBurstUnderTheLimitPasses() throws Exception {
    String sellerToken = bearer(freshUser());
    String buyerToken = bearer(freshUser());
    // Three offers on three distinct listings: each is a fresh live
    // pair, so all three pass — the bucket counts attempts, but under
    // the limit every attempt passes.
    offerOnce(buyerToken, createListingOnce(sellerToken)).andExpect(status().isOk());
    offerOnce(buyerToken, createListingOnce(sellerToken)).andExpect(status().isOk());
    offerOnce(buyerToken, createListingOnce(sellerToken)).andExpect(status().isOk());
  }

  @Test
  void fourthSocialWriteIsRejectedWith429Envelope() throws Exception {
    String sellerToken = bearer(freshUser());
    String buyerToken = bearer(freshUser());
    offerOnce(buyerToken, createListingOnce(sellerToken)).andExpect(status().isOk());
    offerOnce(buyerToken, createListingOnce(sellerToken)).andExpect(status().isOk());
    offerOnce(buyerToken, createListingOnce(sellerToken)).andExpect(status().isOk());
    offerOnce(buyerToken, createListingOnce(sellerToken))
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
  void socialBucketIsSharedAcrossShapes() throws Exception {
    // Exhausting the bucket with offers leaves every other creation
    // shape throttled too — one bucket for the whole social/money
    // surface, not one per endpoint. The filter short-circuits
    // before the controllers, so the unknown ids below answer 429
    // rather than 404 — a denied request consumes nothing, so each
    // shape in turn still sees the empty bucket.
    String sellerToken = bearer(freshUser());
    String buyerToken = bearer(freshUser());
    offerOnce(buyerToken, createListingOnce(sellerToken)).andExpect(status().isOk());
    offerOnce(buyerToken, createListingOnce(sellerToken)).andExpect(status().isOk());
    offerOnce(buyerToken, createListingOnce(sellerToken)).andExpect(status().isOk());
    reviewOnce(buyerToken, 1L).andExpect(status().isTooManyRequests());
    disputeOnce(buyerToken, 1L).andExpect(status().isTooManyRequests());
    evidenceOnce(buyerToken, 1L).andExpect(status().isTooManyRequests());
    appealOnce(buyerToken, 1L).andExpect(status().isTooManyRequests());
  }

  @Test
  void socialBucketIsPerUser() throws Exception {
    // Exhausting one buyer's social bucket leaves another buyer's
    // untouched.
    String sellerToken = bearer(freshUser());
    String firstToken = bearer(freshUser());
    offerOnce(firstToken, createListingOnce(sellerToken)).andExpect(status().isOk());
    offerOnce(firstToken, createListingOnce(sellerToken)).andExpect(status().isOk());
    offerOnce(firstToken, createListingOnce(sellerToken)).andExpect(status().isOk());
    offerOnce(firstToken, createListingOnce(sellerToken))
        .andExpect(status().isTooManyRequests());

    String secondToken = bearer(freshUser());
    offerOnce(secondToken, createListingOnce(sellerToken)).andExpect(status().isOk());
  }

  @Test
  void anonymousSocialWriteFallbackStillThrottles() throws Exception {
    // No token: the filter falls back to a per-IP bucket. The first three
    // anonymous offers are answered 401 by the security chain (the bucket
    // still consumed each attempt); the fourth is short-circuited 429
    // by the filter itself. A dedicated remote address keeps this test's
    // IP bucket away from every other test's.
    String ip = "203.0.113.43";
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(post("/api/items/{id}/offers", 1L)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"priceCents\":10000}")
              .with(request -> {
                request.setRemoteAddr(ip);
                return request;
              }))
          .andExpect(status().isUnauthorized());
    }
    mockMvc.perform(post("/api/items/{id}/offers", 1L)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":10000}")
            .with(request -> {
              request.setRemoteAddr(ip);
              return request;
            }))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }

  @Test
  void socialBucketDoesNotAffectOrderTransitions() throws Exception {
    // Exhausting the social bucket leaves the order-transition bucket
    // untouched: the buyer files three offers, then creates and pays
    // an order without a 429. Order creation itself is a separate
    // bucket too, so it also passes after the social burst.
    String sellerToken = bearer(freshUser());
    String buyerToken = bearer(freshUser());
    offerOnce(buyerToken, createListingOnce(sellerToken)).andExpect(status().isOk());
    offerOnce(buyerToken, createListingOnce(sellerToken)).andExpect(status().isOk());
    offerOnce(buyerToken, createListingOnce(sellerToken)).andExpect(status().isOk());
    offerOnce(buyerToken, createListingOnce(sellerToken))
        .andExpect(status().isTooManyRequests());
    long orderId = createOrderOnce(buyerToken, createListingOnce(sellerToken));
    payOnce(buyerToken, orderId).andExpect(status().isOk());
  }
}
