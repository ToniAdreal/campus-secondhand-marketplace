package com.toni.marketplace.review;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.toni.marketplace.order.Order;
import com.toni.marketplace.order.OrderRepository;
import com.toni.marketplace.order.OrderStatus;
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
 * Rate-limit behavior of review edits and seller responses through
 * the {@link com.toni.marketplace.auth.AuthRateLimitFilter} (backlog
 * #298): {@code PUT /api/reviews/{id}} and {@code PUT
 * /api/reviews/{id}/response} share one per-<em>user</em> bucket
 * (production default 20/minute — pinned to 3/minute here so the
 * burst arithmetic stays small), keyed on the JWT principal id like
 * the message/report buckets, with the per-IP fallback for a
 * missing/invalid token. The bucket never resets on success — a
 * completed edit must not refill the allowance — and it is
 * deliberately separate from the #282 creation bucket (filing a
 * review stays there).
 *
 * <p>Buckets are in-memory and survive across tests within this class,
 * so each test uses fresh users (per-user keys), mirroring
 * {@code AdminResolutionRateLimitTest}. Deliberately NOT
 * {@code @Transactional}, mirroring {@code SellerReviewEditTest}:
 * the test owns its data via its unique usernames and wipes every
 * table it touches in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the
    // one place that exercises the review edit/response surfaces, so
    // re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.review-edit-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the
    // review-edit-bucket assertions.
    "app.auth.login-lockout.enabled=false"})
class ReviewEditRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;
  @Autowired private ItemRepository items;
  @Autowired private OrderRepository orders;
  @Autowired private SellerReviewRepository reviews;
  @Autowired private RefreshTokenRepository refreshTokens;

  @AfterEach
  void cleanUp() {
    reviews.deleteAll();
    orders.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User freshUser(String role) {
    String tag = "rvedit-" + role + "-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private Order completedOrder(User seller, User buyer) {
    Item item = new Item("Review thing " + UUID.randomUUID().toString().substring(0, 8),
        "seeded", 5000L, seller.getId());
    item.setStatus(ItemStatus.SOLD);
    items.save(item);
    Order order = new Order(item, buyer.getId(), 5000L);
    order.setStatus(OrderStatus.COMPLETED);
    return orders.save(order);
  }

  private SellerReview seedReview(User seller, User buyer) {
    Order order = completedOrder(seller, buyer);
    return reviews.save(new SellerReview(order.getId(),
        buyer.getId(), seller.getId(), 4, "good seller"));
  }

  private void edit(String buyerToken, long reviewId, int expectedStatus) throws Exception {
    mockMvc.perform(put("/api/reviews/{id}", reviewId)
            .header("Authorization", "Bearer " + buyerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"rating\":5,\"body\":\"even better\"}"))
        .andExpect(status().is(expectedStatus));
  }

  private void respond(String sellerToken, long reviewId, int expectedStatus) throws Exception {
    mockMvc.perform(put("/api/reviews/{id}/response", reviewId)
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"response\":\"Thanks for the kind words!\"}"))
        .andExpect(status().is(expectedStatus));
  }

  @Test
  void editBurstIs429() throws Exception {
    String buyerToken = token(freshUser("buyer"));

    // Three bogus edits consume the bucket (404: no review edited).
    for (int i = 0; i < 3; i++) {
      edit(buyerToken, 999_999L, 404);
    }
    // The bucket is empty: the fourth edit is throttled before the
    // controller ever sees it.
    mockMvc.perform(put("/api/reviews/{id}", 999_999L)
            .header("Authorization", "Bearer " + buyerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"rating\":5,\"body\":\"even better\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header -> {
          String retryAfter = header.getResponse().getHeader("Retry-After");
          if (retryAfter == null) {
            throw new AssertionError("missing Retry-After header on the 429");
          }
        })
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  void singleEditPasses() throws Exception {
    User seller = freshUser("seller");
    User buyer = freshUser("buyer");
    SellerReview review = seedReview(seller, buyer);

    mockMvc.perform(put("/api/reviews/{id}", review.getId())
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"rating\":5,\"body\":\"even better\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.rating").value(5))
        .andExpect(jsonPath("$.data.body").value("even better"));
  }

  @Test
  void editBucketIsSharedAcrossEditAndResponseShapes() throws Exception {
    // Exhausting the bucket with edits leaves a response throttled
    // too — one bucket for both PUT shapes, not one per endpoint.
    // A throttled response answers nothing, so a real response by a
    // fresh seller still lands below.
    String buyerToken = token(freshUser("buyer"));
    for (int i = 0; i < 3; i++) {
      edit(buyerToken, 999_999L, 404);
    }
    // The response shape shares the buyer's bucket key only when the
    // same principal calls it; here the same token proves the bucket
    // is shared across shapes, not per path.
    respond(buyerToken, 999_999L, 429);
  }

  @Test
  void singleResponsePasses() throws Exception {
    User seller = freshUser("seller");
    User buyer = freshUser("buyer");
    SellerReview review = seedReview(seller, buyer);

    mockMvc.perform(put("/api/reviews/{id}/response", review.getId())
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"response\":\"Thanks for the kind words!\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sellerResponse").value("Thanks for the kind words!"));
  }

  @Test
  void editBucketIsPerUser() throws Exception {
    // Exhausting one buyer's bucket leaves another buyer's untouched.
    String firstBuyerToken = token(freshUser("buyer"));
    for (int i = 0; i < 3; i++) {
      edit(firstBuyerToken, 999_999L, 404);
    }
    edit(firstBuyerToken, 999_999L, 429);

    edit(token(freshUser("buyer")), 999_999L, 404);
  }

  @Test
  void reviewCreationBucketUnaffected() throws Exception {
    // Exhausting the edit bucket must not starve review creation:
    // filing a review stays in the #282 bucket, and a fresh completed
    // order still accepts one.
    User seller = freshUser("seller");
    User buyer = freshUser("buyer");
    Order freshOrder = completedOrder(seller, buyer);
    String buyerToken = token(buyer);

    for (int i = 0; i < 3; i++) {
      edit(buyerToken, 999_999L, 404);
    }
    edit(buyerToken, 999_999L, 429);

    mockMvc.perform(post("/api/orders/{id}/review", freshOrder.getId())
            .header("Authorization", "Bearer " + buyerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"rating\":5,\"body\":\"great seller\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.rating").value(5));
  }
}
