package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.order.IdempotencyKeyRepository;
import com.toni.marketplace.order.OrderRepository;
import com.toni.marketplace.order.PaymentAttemptRepository;
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
 * Rate-limit behavior of the order transition and item mutation POSTs
 * through {@code AuthRateLimitFilter} (backlog #228): {@code POST
 * /api/orders/{id}/pay}, {@code /cancel}, {@code /complete},
 * {@code /refund}, {@code /ship} share one per-<em>user</em> bucket and
 * {@code POST /api/items/{id}/photo}, {@code /photos}, {@code /renew},
 * {@code /publish} plus {@code PATCH /api/items/{id}/status} share another
 * per-<em>user</em> bucket (production defaults 20/minute for transitions,
 * 30/minute for mutations — pinned to 3/minute here so the burst
 * arithmetic stays small), both keyed on the JWT principal id like the
 * message/report buckets, with the per-IP fallback for a missing/invalid
 * token. The buckets never reset on success — an idempotent re-pay must
 * not refill the transition allowance, and a successful upload must not
 * refill the mutation allowance.
 *
 * <p>Buckets are in-memory and survive across tests within this class, so
 * each test uses fresh users (per-user keys), mirroring
 * {@code ListingOrderCreateRateLimitTest}.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@code ListingOrderCreateRateLimitTest}: order creation reserves its
 * idempotency key in a {@code REQUIRES_NEW} transaction whose foreign
 * keys can only see rows that are already committed. The test owns its
 * data via its unique usernames and wipes every table it touches in
 * {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the transition/mutation surfaces, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.order-transition-attempts-per-minute=3",
    "app.auth.rate-limit.item-mutation-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the
    // bucket assertions.
    "app.auth.login-lockout.enabled=false",
    "app.uploads.dir=target/test-uploads/transition-mutation"})
class OrderTransitionItemMutationRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();
  private static final Path UPLOAD_DIR = Paths.get("target/test-uploads/transition-mutation");

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

  @AfterEach
  void cleanUp() throws IOException {
    // FK-safe order: payment attempts and idempotency rows first (their
    // order FKs point at orders), then orders, notifications and refresh
    // tokens (all point at users), then items (photos cascade), then users.
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
    String tag = "transmut-" + SEQ.incrementAndGet() + "-"
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

  private ResultActions payOnce(String accessToken, long orderId) throws Exception {
    return mockMvc.perform(post("/api/orders/{id}/pay", orderId)
        .header("Authorization", "Bearer " + accessToken));
  }

  private ResultActions addPhotoOnce(String accessToken, long itemId) throws Exception {
    return mockMvc.perform(multipart("/api/items/{id}/photos", itemId)
        .file(new MockMultipartFile("file", "camera.png", "image/png", PNG_BYTES))
        .header("Authorization", "Bearer " + accessToken));
  }

  @Test
  void payBurstUnderTheLimitPassesIdempotently() throws Exception {
    User seller = freshUser();
    User buyer = freshUser();
    long orderId = createOrderOnce(bearer(buyer), createListingOnce(bearer(seller)));
    String buyerToken = bearer(buyer);
    // First pay captures; the next two are idempotent re-pays, not 429s —
    // the bucket counts attempts, but under the limit every attempt passes.
    payOnce(buyerToken, orderId).andExpect(status().isOk());
    payOnce(buyerToken, orderId).andExpect(status().isOk());
    payOnce(buyerToken, orderId).andExpect(status().isOk());
  }

  @Test
  void fourthTransitionIsRejectedWith429Envelope() throws Exception {
    User seller = freshUser();
    User buyer = freshUser();
    long orderId = createOrderOnce(bearer(buyer), createListingOnce(bearer(seller)));
    String buyerToken = bearer(buyer);
    payOnce(buyerToken, orderId).andExpect(status().isOk());
    payOnce(buyerToken, orderId).andExpect(status().isOk());
    payOnce(buyerToken, orderId).andExpect(status().isOk());
    payOnce(buyerToken, orderId)
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
  void transitionBucketIsSharedAcrossEndpoints() throws Exception {
    // Exhausting the bucket with pays leaves a different transition
    // (cancel on a fresh PENDING order) throttled too — one bucket for
    // the whole money path, not one per endpoint.
    User seller = freshUser();
    User buyer = freshUser();
    String sellerToken = bearer(seller);
    String buyerToken = bearer(buyer);
    long paidOrder = createOrderOnce(buyerToken, createListingOnce(sellerToken));
    long pendingOrder = createOrderOnce(buyerToken, createListingOnce(sellerToken));
    payOnce(buyerToken, paidOrder).andExpect(status().isOk());
    payOnce(buyerToken, paidOrder).andExpect(status().isOk());
    payOnce(buyerToken, paidOrder).andExpect(status().isOk());
    mockMvc.perform(post("/api/orders/{id}/cancel", pendingOrder)
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void transitionBucketIsPerUser() throws Exception {
    // Exhausting one buyer's transition bucket leaves another buyer's
    // untouched.
    User seller = freshUser();
    User first = freshUser();
    User second = freshUser();
    String sellerToken = bearer(seller);
    String firstToken = bearer(first);
    long orderId = createOrderOnce(firstToken, createListingOnce(sellerToken));
    payOnce(firstToken, orderId).andExpect(status().isOk());
    payOnce(firstToken, orderId).andExpect(status().isOk());
    payOnce(firstToken, orderId).andExpect(status().isOk());
    payOnce(firstToken, orderId).andExpect(status().isTooManyRequests());

    long secondOrder = createOrderOnce(bearer(second), createListingOnce(sellerToken));
    payOnce(bearer(second), secondOrder).andExpect(status().isOk());
  }

  @Test
  void anonymousTransitionFallbackStillThrottles() throws Exception {
    // No token: the filter falls back to a per-IP bucket. The first three
    // anonymous pays are answered 401 by the security chain (the bucket
    // still consumed each attempt); the fourth is short-circuited 429
    // by the filter itself. A dedicated remote address keeps this test's
    // IP bucket away from every other test's.
    String ip = "203.0.113.41";
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(post("/api/orders/{id}/pay", 1L)
              .with(request -> {
                request.setRemoteAddr(ip);
                return request;
              }))
          .andExpect(status().isUnauthorized());
    }
    mockMvc.perform(post("/api/orders/{id}/pay", 1L)
            .with(request -> {
              request.setRemoteAddr(ip);
              return request;
            }))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }

  @Test
  void photoBurstUnderTheLimitPasses() throws Exception {
    String sellerToken = bearer(freshUser());
    long itemId = createListingOnce(sellerToken);
    addPhotoOnce(sellerToken, itemId).andExpect(status().isOk());
    addPhotoOnce(sellerToken, itemId).andExpect(status().isOk());
    addPhotoOnce(sellerToken, itemId).andExpect(status().isOk());
  }

  @Test
  void fourthMutationIsRejectedWith429Envelope() throws Exception {
    String sellerToken = bearer(freshUser());
    long itemId = createListingOnce(sellerToken);
    addPhotoOnce(sellerToken, itemId).andExpect(status().isOk());
    addPhotoOnce(sellerToken, itemId).andExpect(status().isOk());
    addPhotoOnce(sellerToken, itemId).andExpect(status().isOk());
    addPhotoOnce(sellerToken, itemId)
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
  void mutationBucketIsSharedAcrossEndpoints() throws Exception {
    // Exhausting the bucket with photo uploads leaves a different
    // mutation (the PATCH status flip on another listing) throttled too.
    User seller = freshUser();
    String sellerToken = bearer(seller);
    long galleryItem = createListingOnce(sellerToken);
    long statusItem = createListingOnce(sellerToken);
    addPhotoOnce(sellerToken, galleryItem).andExpect(status().isOk());
    addPhotoOnce(sellerToken, galleryItem).andExpect(status().isOk());
    addPhotoOnce(sellerToken, galleryItem).andExpect(status().isOk());
    mockMvc.perform(patch("/api/items/{id}/status", statusItem)
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"SOLD\"}"))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void mutationBucketIsPerUser() throws Exception {
    // Exhausting one seller's mutation bucket leaves another seller's
    // untouched.
    User first = freshUser();
    String firstToken = bearer(first);
    long firstItem = createListingOnce(firstToken);
    addPhotoOnce(firstToken, firstItem).andExpect(status().isOk());
    addPhotoOnce(firstToken, firstItem).andExpect(status().isOk());
    addPhotoOnce(firstToken, firstItem).andExpect(status().isOk());
    addPhotoOnce(firstToken, firstItem).andExpect(status().isTooManyRequests());

    String secondToken = bearer(freshUser());
    addPhotoOnce(secondToken, createListingOnce(secondToken)).andExpect(status().isOk());
  }

  @Test
  void anonymousMutationFallbackStillThrottles() throws Exception {
    // Same per-IP fallback shape as the transition bucket: three
    // anonymous uploads 401, the fourth is filter-short-circuited 429.
    String ip = "203.0.113.42";
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(multipart("/api/items/{id}/photos", 1L)
              .file(new MockMultipartFile("file", "camera.png", "image/png", PNG_BYTES))
              .with(request -> {
                request.setRemoteAddr(ip);
                return request;
              }))
          .andExpect(status().isUnauthorized());
    }
    mockMvc.perform(multipart("/api/items/{id}/photos", 1L)
            .file(new MockMultipartFile("file", "camera.png", "image/png", PNG_BYTES))
            .with(request -> {
              request.setRemoteAddr(ip);
              return request;
            }))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }

  @Test
  void transitionAndMutationBucketsAreIndependent() throws Exception {
    // Exhausting one bucket leaves the other untouched, in both
    // directions: transition traffic must not starve mutations, and
    // mutation traffic must not starve transitions.
    User seller = freshUser();
    User buyer = freshUser();
    String sellerToken = bearer(seller);
    String buyerToken = bearer(buyer);
    long orderId = createOrderOnce(buyerToken, createListingOnce(sellerToken));
    long buyerListing = createListingOnce(buyerToken);
    payOnce(buyerToken, orderId).andExpect(status().isOk());
    payOnce(buyerToken, orderId).andExpect(status().isOk());
    payOnce(buyerToken, orderId).andExpect(status().isOk());
    payOnce(buyerToken, orderId).andExpect(status().isTooManyRequests());
    // The buyer's mutation bucket is untouched by the transition burst.
    addPhotoOnce(buyerToken, buyerListing).andExpect(status().isOk());

    User secondSeller = freshUser();
    String secondSellerToken = bearer(secondSeller);
    long secondItem = createListingOnce(secondSellerToken);
    addPhotoOnce(secondSellerToken, secondItem).andExpect(status().isOk());
    addPhotoOnce(secondSellerToken, secondItem).andExpect(status().isOk());
    addPhotoOnce(secondSellerToken, secondItem).andExpect(status().isOk());
    addPhotoOnce(secondSellerToken, secondItem).andExpect(status().isTooManyRequests());
    // The same user's transition bucket is untouched by their mutation
    // burst: the seller, now acting as buyer on a third seller's
    // listing, pays without a 429.
    long secondOrder = createOrderOnce(secondSellerToken,
        createListingOnce(bearer(freshUser())));
    payOnce(secondSellerToken, secondOrder).andExpect(status().isOk());
  }
}
