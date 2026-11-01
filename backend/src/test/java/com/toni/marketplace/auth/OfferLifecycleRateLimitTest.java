package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.offer.PriceOfferRepository;
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
 * Rate-limit behavior of the offer lifecycle POSTs through
 * {@code AuthRateLimitFilter} (backlog #293): {@code POST
 * /api/offers/{id}/accept}, {@code /decline}, {@code /counter},
 * {@code /counter-accept}, {@code /counter-decline} and
 * {@code /withdraw} share one per-<em>user</em> bucket (production
 * default 20/minute — pinned to 3/minute here so the burst arithmetic
 * stays small), keyed on the JWT principal id like the message/report
 * buckets, with the per-IP fallback for a missing/invalid token. The
 * bucket never resets on success — an accepted, declined, countered
 * or withdrawn offer must not refill the allowance — and it is
 * deliberately separate from the #282 creation bucket (filing an
 * offer stays there) and the #228 order-transition bucket, so
 * lifecycle traffic can never starve either, nor vice versa.
 *
 * <p>Buckets are in-memory and survive across tests within this class, so
 * each test uses fresh users (per-user keys), mirroring
 * {@code OrderReceiptRateLimitTest}. Every offer in a test comes from a
 * distinct buyer: a single buyer filing four offers would trip the
 * pinned #282 creation bucket before the lifecycle bucket is ever
 * reached, and this class is about the lifecycle bucket, not that one.
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
    // place that exercises the offer lifecycle surfaces, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.offer-lifecycle-attempts-per-minute=3",
    // Pin the neighbouring buckets to the same small size so the
    // independence tests can exhaust them without 20 writes.
    "app.auth.rate-limit.social-write-attempts-per-minute=3",
    "app.auth.rate-limit.order-transition-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the
    // bucket assertions.
    "app.auth.login-lockout.enabled=false"})
class OfferLifecycleRateLimitTest {

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
  @Autowired private PriceOfferRepository offers;

  @AfterEach
  void cleanUp() {
    // FK-safe order: payout and payment-attempt rows first (their order
    // FKs point at orders), then idempotency rows, notifications and
    // offers, then orders, refresh tokens, items, users.
    payouts.deleteAll();
    paymentAttempts.deleteAll();
    idempotencyKeys.deleteAll();
    notifications.deleteAll();
    offers.deleteAll();
    orders.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User freshUser() {
    String tag = "offerlife-" + SEQ.incrementAndGet() + "-"
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

  private long createOfferOnce(String accessToken, long itemId) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/items/{id}/offers", itemId)
            .header("Authorization", "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":10000}"))
        .andExpect(status().isOk())
        .andReturn();
    return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.id"))
        .longValue();
  }

  /** A fresh PENDING offer on one of the seller's listings, from a fresh buyer. */
  private long freshPendingOffer(String sellerToken) throws Exception {
    return createOfferOnce(bearer(freshUser()), createListingOnce(sellerToken));
  }

  private ResultActions declineOnce(String accessToken, long offerId) throws Exception {
    return mockMvc.perform(post("/api/offers/{id}/decline", offerId)
        .header("Authorization", "Bearer " + accessToken));
  }

  private ResultActions counterOnce(String accessToken, long offerId) throws Exception {
    return mockMvc.perform(post("/api/offers/{id}/counter", offerId)
        .header("Authorization", "Bearer " + accessToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"priceCents\":20000}"));
  }

  private ResultActions acceptOnce(String accessToken, long offerId) throws Exception {
    return mockMvc.perform(post("/api/offers/{id}/accept", offerId)
        .header("Authorization", "Bearer " + accessToken));
  }

  private ResultActions counterAcceptOnce(String accessToken, long offerId) throws Exception {
    return mockMvc.perform(post("/api/offers/{id}/counter-accept", offerId)
        .header("Authorization", "Bearer " + accessToken));
  }

  private ResultActions withdrawOnce(String accessToken, long offerId) throws Exception {
    return mockMvc.perform(post("/api/offers/{id}/withdraw", offerId)
        .header("Authorization", "Bearer " + accessToken));
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

  @Test
  void lifecycleBurstUnderTheLimitPasses() throws Exception {
    String sellerToken = bearer(freshUser());
    // Three declines on three distinct offers: under the limit every
    // attempt passes, and the bucket counts attempts, not successes.
    declineOnce(sellerToken, freshPendingOffer(sellerToken)).andExpect(status().isOk());
    declineOnce(sellerToken, freshPendingOffer(sellerToken)).andExpect(status().isOk());
    declineOnce(sellerToken, freshPendingOffer(sellerToken)).andExpect(status().isOk());
  }

  @Test
  void fourthLifecycleAttemptIsRejectedWith429Envelope() throws Exception {
    String sellerToken = bearer(freshUser());
    declineOnce(sellerToken, freshPendingOffer(sellerToken)).andExpect(status().isOk());
    declineOnce(sellerToken, freshPendingOffer(sellerToken)).andExpect(status().isOk());
    declineOnce(sellerToken, freshPendingOffer(sellerToken)).andExpect(status().isOk());
    declineOnce(sellerToken, freshPendingOffer(sellerToken))
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
  void lifecycleBucketIsSharedAcrossEndpoints() throws Exception {
    // Exhausting the bucket with declines and a counter leaves an
    // accept throttled too — one bucket for the whole lifecycle, not
    // one per endpoint. The counter price (20000) sits strictly
    // between the buyer's offer (10000) and the asking price (25000).
    String sellerToken = bearer(freshUser());
    declineOnce(sellerToken, freshPendingOffer(sellerToken)).andExpect(status().isOk());
    declineOnce(sellerToken, freshPendingOffer(sellerToken)).andExpect(status().isOk());
    counterOnce(sellerToken, freshPendingOffer(sellerToken)).andExpect(status().isOk());
    acceptOnce(sellerToken, freshPendingOffer(sellerToken))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void lifecycleBucketIsSharedAcrossBuyerEndpoints() throws Exception {
    // The buyer side shares the same per-user bucket across its three
    // shapes: a withdraw, a counter-accept and a counter-decline from
    // one buyer exhaust it, and a fourth buyer transition is 429.
    // Each offer lives on its own listing so the transitions do not
    // interfere with each other.
    User seller = freshUser();
    User buyer = freshUser();
    String sellerToken = bearer(seller);
    String buyerToken = bearer(buyer);
    long withdrawn = createOfferOnce(buyerToken, createListingOnce(sellerToken));
    withdrawOnce(buyerToken, withdrawn).andExpect(status().isOk());
    long accepted = createOfferOnce(buyerToken, createListingOnce(sellerToken));
    counterOnce(sellerToken, accepted).andExpect(status().isOk());
    counterAcceptOnce(buyerToken, accepted).andExpect(status().isOk());
    // The counter above consumed the seller's bucket, not the buyer's;
    // the buyer's third attempt is a counter-decline on a fresh
    // countered offer, and the fourth is 429.
    long declined = createOfferOnce(buyerToken, createListingOnce(sellerToken));
    counterOnce(sellerToken, declined).andExpect(status().isOk());
    mockMvc.perform(post("/api/offers/{id}/counter-decline", declined)
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk());
    // A fourth buyer transition needs no fourth offer: the filter
    // short-circuits before the controller, so an unknown id answers
    // 429 rather than 404 — and a fifth offer filing would trip the
    // pinned #282 creation bucket first, which is not this test.
    withdrawOnce(buyerToken, 999_999L).andExpect(status().isTooManyRequests());
  }

  @Test
  void lifecycleBucketIsPerUser() throws Exception {
    // Exhausting one seller's lifecycle bucket leaves another seller's
    // untouched.
    String firstSellerToken = bearer(freshUser());
    declineOnce(firstSellerToken, freshPendingOffer(firstSellerToken))
        .andExpect(status().isOk());
    declineOnce(firstSellerToken, freshPendingOffer(firstSellerToken))
        .andExpect(status().isOk());
    declineOnce(firstSellerToken, freshPendingOffer(firstSellerToken))
        .andExpect(status().isOk());
    declineOnce(firstSellerToken, freshPendingOffer(firstSellerToken))
        .andExpect(status().isTooManyRequests());

    String secondSellerToken = bearer(freshUser());
    declineOnce(secondSellerToken, freshPendingOffer(secondSellerToken))
        .andExpect(status().isOk());
  }

  @Test
  void lifecycleBucketIsIndependentOfCreationBucket() throws Exception {
    // Exhausting the lifecycle bucket leaves the #282 creation bucket
    // untouched: the same user, who just declined three offers as a
    // seller, files an offer as a buyer without a 429.
    User user = freshUser();
    String userToken = bearer(user);
    declineOnce(userToken, freshPendingOffer(userToken)).andExpect(status().isOk());
    declineOnce(userToken, freshPendingOffer(userToken)).andExpect(status().isOk());
    declineOnce(userToken, freshPendingOffer(userToken)).andExpect(status().isOk());
    declineOnce(userToken, freshPendingOffer(userToken))
        .andExpect(status().isTooManyRequests());
    createOfferOnce(userToken, createListingOnce(bearer(freshUser())));
  }

  @Test
  void creationBucketExhaustionLeavesLifecycleBucketIntact() throws Exception {
    // Reverse direction: exhausting the #282 creation bucket (three
    // offers filed as a buyer) leaves the lifecycle bucket untouched —
    // the same user declines an offer on their own listing.
    User user = freshUser();
    String userToken = bearer(user);
    String otherSellerToken = bearer(freshUser());
    createOfferOnce(userToken, createListingOnce(otherSellerToken));
    createOfferOnce(userToken, createListingOnce(otherSellerToken));
    createOfferOnce(userToken, createListingOnce(otherSellerToken));
    mockMvc.perform(post("/api/items/{id}/offers", createListingOnce(otherSellerToken))
            .header("Authorization", "Bearer " + userToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":10000}"))
        .andExpect(status().isTooManyRequests());
    declineOnce(userToken, freshPendingOffer(userToken)).andExpect(status().isOk());
  }

  @Test
  void lifecycleBucketIsIndependentOfOrderTransitionBucket() throws Exception {
    // Exhausting the lifecycle bucket leaves the #228 order-transition
    // bucket untouched: the same user, who just declined three offers
    // as a seller, pays an order as a buyer without a 429.
    User user = freshUser();
    String userToken = bearer(user);
    declineOnce(userToken, freshPendingOffer(userToken)).andExpect(status().isOk());
    declineOnce(userToken, freshPendingOffer(userToken)).andExpect(status().isOk());
    declineOnce(userToken, freshPendingOffer(userToken)).andExpect(status().isOk());
    declineOnce(userToken, freshPendingOffer(userToken))
        .andExpect(status().isTooManyRequests());
    long orderId = createOrderOnce(userToken, createListingOnce(bearer(freshUser())));
    payOnce(userToken, orderId);
  }

  @Test
  void orderTransitionBucketExhaustionLeavesLifecycleBucketIntact() throws Exception {
    // Reverse direction: exhausting the #228 transition bucket (three
    // pays as a buyer) leaves the lifecycle bucket untouched — the
    // same user declines an offer on their own listing.
    User user = freshUser();
    String userToken = bearer(user);
    String sellerToken = bearer(freshUser());
    payOnce(userToken, createOrderOnce(userToken, createListingOnce(sellerToken)));
    payOnce(userToken, createOrderOnce(userToken, createListingOnce(sellerToken)));
    payOnce(userToken, createOrderOnce(userToken, createListingOnce(sellerToken)));
    long fourthOrder = createOrderOnce(userToken, createListingOnce(sellerToken));
    mockMvc.perform(post("/api/orders/{id}/pay", fourthOrder)
            .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
    declineOnce(userToken, freshPendingOffer(userToken)).andExpect(status().isOk());
  }

  @Test
  void anonymousLifecycleFallbackStillThrottles() throws Exception {
    // No token: the filter falls back to a per-IP bucket. The first three
    // anonymous declines are answered 401 by the security chain (the
    // bucket still consumed each attempt); the fourth is short-circuited
    // 429 by the filter itself. A dedicated remote address keeps this
    // test's IP bucket away from every other test's.
    String ip = "203.0.113.44";
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(post("/api/offers/{id}/decline", 1L)
              .with(request -> {
                request.setRemoteAddr(ip);
                return request;
              }))
          .andExpect(status().isUnauthorized());
    }
    mockMvc.perform(post("/api/offers/{id}/decline", 1L)
            .with(request -> {
              request.setRemoteAddr(ip);
              return request;
            }))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }
}
