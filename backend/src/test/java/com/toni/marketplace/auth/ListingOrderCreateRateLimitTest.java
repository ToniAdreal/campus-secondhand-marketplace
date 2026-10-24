package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.order.IdempotencyKeyRepository;
import com.toni.marketplace.order.OrderRepository;
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
 * Rate-limit behavior of listing and order creation through the
 * {@code AuthRateLimitFilter} (backlog #219): {@code POST /api/items} and
 * {@code POST /api/orders} get their own per-<em>user</em> buckets
 * (production defaults 10/minute for listings, 20/minute for orders —
 * pinned to 3/minute here so the burst arithmetic stays small), keyed on
 * the JWT principal id like the message/report buckets, with the per-IP
 * fallback for a missing/invalid token. The buckets never reset on
 * success — a created listing or a placed order must not refill the
 * spam allowance.
 *
 * <p>Buckets are in-memory and survive across tests within this class, so
 * each test uses fresh users (per-user keys), mirroring
 * {@code StepUpRateLimitTest}.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@code AccountClosureOrderFlowTest} and {@code OrderIdempotencyTest}:
 * order creation reserves its idempotency key in a {@code REQUIRES_NEW}
 * transaction whose foreign keys ({@code fk_idem_user} on the buyer)
 * can only see rows that are already committed. Inside a wrapping test
 * transaction the registered users never commit, so the reservation
 * fails its FK check and {@code POST /api/orders} answers 500
 * ("idempotency reservation lost"). Running the flow the way production
 * runs it (every step commits in its own transaction) removes the whole
 * class of failure; the test owns its data via its unique usernames and
 * wipes every table it touches in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the listing/order create surfaces, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.listing-create-attempts-per-minute=3",
    "app.auth.rate-limit.order-create-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the
    // bucket assertions.
    "app.auth.login-lockout.enabled=false"
})
class ListingOrderCreateRateLimitTest {

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

  @AfterEach
  void cleanUp() {
    // FK-safe order: idempotency rows first (their order_id FK points at
    // orders), then orders, notifications and refresh tokens (all point at
    // users), then items, then users.
    idempotencyKeys.deleteAll();
    orders.deleteAll();
    notifications.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User freshUser() {
    String tag = "createrl-" + SEQ.incrementAndGet() + "-"
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

  private ResultActions createOrderOnce(String accessToken, long itemId) throws Exception {
    return mockMvc.perform(post("/api/orders")
        .header("Authorization", "Bearer " + accessToken)
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"itemId\":" + itemId + "}"));
  }

  @Test
  void listingBurstUnderTheLimitPasses() throws Exception {
    String token = bearer(freshUser());
    createListingOnce(token);
    createListingOnce(token);
    createListingOnce(token);
  }

  @Test
  void fourthListingCreateIsRejectedWith429Envelope() throws Exception {
    String token = bearer(freshUser());
    createListingOnce(token);
    createListingOnce(token);
    createListingOnce(token);
    mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"ThinkPad T480s\",\"description\":\"good battery\","
                + "\"priceCents\":25000}"))
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
  void listingBucketIsPerUser() throws Exception {
    // Exhausting one user's listing bucket leaves another user's untouched.
    String first = bearer(freshUser());
    createListingOnce(first);
    createListingOnce(first);
    createListingOnce(first);
    mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + first)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"ThinkPad T480s\",\"description\":\"good battery\","
                + "\"priceCents\":25000}"))
        .andExpect(status().isTooManyRequests());

    createListingOnce(bearer(freshUser()));
  }

  @Test
  void orderBurstUnderTheLimitPasses() throws Exception {
    User seller = freshUser();
    User buyer = freshUser();
    String sellerToken = bearer(seller);
    String buyerToken = bearer(buyer);
    long first = createListingOnce(sellerToken);
    long second = createListingOnce(sellerToken);
    long third = createListingOnce(sellerToken);
    // A buyer cannot order their own listing (422) — the seller is a
    // different account, and each order targets a distinct listing so no
    // active-order 409 masks the rate-limit assertions.
    createOrderOnce(buyerToken, first).andExpect(status().isOk());
    createOrderOnce(buyerToken, second).andExpect(status().isOk());
    createOrderOnce(buyerToken, third).andExpect(status().isOk());
  }

  @Test
  void fourthOrderCreateIsRejectedWith429Envelope() throws Exception {
    User seller = freshUser();
    User buyer = freshUser();
    String buyerToken = bearer(buyer);
    // One listing per order: a second order on the same listing would
    // answer 409 (active-order guard), not the rate limit.
    long first = createListingOnce(bearer(seller));
    long second = createListingOnce(bearer(seller));
    long third = createListingOnce(bearer(seller));
    createOrderOnce(buyerToken, first).andExpect(status().isOk());
    createOrderOnce(buyerToken, second).andExpect(status().isOk());
    createOrderOnce(buyerToken, third).andExpect(status().isOk());
    mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + buyerToken)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + first + "}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(header -> {
          String retryAfter = header.getResponse().getHeader("Retry-After");
          if (retryAfter == null) {
            throw new AssertionError("missing Retry-After header on the 429");
          }
        });
  }

  @Test
  void orderAndListingBucketsAreIndependent() throws Exception {
    // Exhausting the listing bucket leaves the same user's order bucket
    // untouched — and vice versa: each surface has its own ceiling.
    User seller = freshUser();
    User buyer = freshUser();
    String sellerToken = bearer(seller);
    String buyerToken = bearer(buyer);

    createListingOnce(buyerToken);
    createListingOnce(buyerToken);
    createListingOnce(buyerToken);
    mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + buyerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"ThinkPad T480s\",\"description\":\"good battery\","
                + "\"priceCents\":25000}"))
        .andExpect(status().isTooManyRequests());

    long itemId = createListingOnce(sellerToken);
    createOrderOnce(buyerToken, itemId).andExpect(status().isOk());
  }

  @Test
  void orderBucketIsPerUser() throws Exception {
    // Exhausting one buyer's order bucket leaves another buyer's untouched.
    User seller = freshUser();
    User first = freshUser();
    String firstToken = bearer(first);
    // A second seller supplies the fresh listing the second buyer orders:
    // the first buyer's three orders each hold their listing's active
    // order, so reusing those rows would answer 409, not the rate limit.
    String sellerToken = bearer(seller);
    String secondSellerToken = bearer(freshUser());
    long firstItem = createListingOnce(sellerToken);
    long secondItem = createListingOnce(sellerToken);
    long thirdItem = createListingOnce(sellerToken);
    long fourthItem = createListingOnce(secondSellerToken);
    createOrderOnce(firstToken, firstItem).andExpect(status().isOk());
    createOrderOnce(firstToken, secondItem).andExpect(status().isOk());
    createOrderOnce(firstToken, thirdItem).andExpect(status().isOk());
    createOrderOnce(firstToken, fourthItem).andExpect(status().isTooManyRequests());

    createOrderOnce(bearer(freshUser()), fourthItem).andExpect(status().isOk());
  }

  @Test
  void anonymousCreateStill401sBeforeTheBucketMatters() throws Exception {
    // The filter throttles anonymous hammering by IP, but a first-time
    // anonymous create is answered 401 by the security chain — never 429.
    mockMvc.perform(post("/api/items")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"ThinkPad T480s\",\"description\":\"good battery\","
                + "\"priceCents\":25000}"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/orders")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":1}"))
        .andExpect(status().isUnauthorized());
  }
}
