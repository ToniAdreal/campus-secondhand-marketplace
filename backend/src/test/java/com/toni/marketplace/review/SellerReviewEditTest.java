package com.toni.marketplace.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Buyer review edits (backlog #254): PUT /api/reviews/{id} is
 * buyer-only inside {@code app.reviews.edit-window} (default 15
 * minutes from creation, boundary behaviour pinned by the
 * {@code ReviewServiceEditWindowTest} unit test's fixed clock), 422
 * once the window closes or once the seller has responded, and the
 * seller-profile aggregate recomputes from the edited rating because
 * it is computed from the rows on every read, never stored.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@code MessageEditDeleteTest}: the window is exercised by backdating
 * {@code created_at} through JDBC (the column is {@code updatable =
 * false} for JPA — the write instant is a fact, only the test moves
 * it), against the real clock the service reads: 14 minutes old edits
 * fine, 16 minutes old is 422.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SellerReviewEditTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private ItemRepository itemRepo;
  @Autowired private OrderRepository orderRepo;
  @Autowired private SellerReviewRepository reviewRepo;
  @Autowired private RefreshTokenRepository refreshTokens;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbc;

  private User seller;
  private User buyer;
  private User other;
  private String buyerToken;
  private String sellerToken;
  private String otherToken;

  @BeforeEach
  void createUsers() {
    String tag = "rve-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    seller = users.save(new User(tag + "-seller", tag + "-seller@example.com",
        passwords.encode("seller-password")));
    buyer = users.save(new User(tag + "-buyer", tag + "-buyer@example.com",
        passwords.encode("buyer-password")));
    other = users.save(new User(tag + "-other", tag + "-other@example.com",
        passwords.encode("other-password")));
    buyerToken = jwt.createTokenPair(buyer).accessToken();
    sellerToken = jwt.createTokenPair(seller).accessToken();
    otherToken = jwt.createTokenPair(other).accessToken();
  }

  @AfterEach
  void cleanUp() {
    reviewRepo.deleteAll();
    refreshTokens.deleteAll();
    orderRepo.deleteAll();
    itemRepo.deleteAll();
    users.deleteAll();
  }

  private SellerReview seedReview(int rating, String body) {
    Item item = new Item("Edit thing " + UUID.randomUUID().toString().substring(0, 8),
        "seeded", 5000L, seller.getId());
    item.setStatus(ItemStatus.SOLD);
    itemRepo.save(item);
    Order order = new Order(item, buyer.getId(), 5000L);
    order.setStatus(OrderStatus.COMPLETED);
    orderRepo.save(order);
    return reviewRepo.save(new SellerReview(order.getId(),
        buyer.getId(), seller.getId(), rating, body));
  }

  private void backdate(SellerReview review, Duration age) {
    jdbc.update("update seller_review set created_at = ? where id = ?",
        Timestamp.from(Instant.now().minus(age)), review.getId());
  }

  private String editBody(Integer rating, String body) throws Exception {
    var node = objectMapper.createObjectNode();
    if (rating != null) {
      node.put("rating", rating);
    }
    if (body != null) {
      node.put("body", body);
    }
    return objectMapper.writeValueAsString(node);
  }

  private void edit(String token, long reviewId, String json, int expectedStatus)
      throws Exception {
    var call = mockMvc.perform(put("/api/reviews/{id}", reviewId)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json))
        .andExpect(status().is(expectedStatus))
        .andExpect(jsonPath("$.code").value(expectedStatus == 200 ? 0 : expectedStatus));
    if (expectedStatus == 200) {
      call.andExpect(jsonPath("$.data.id").value(reviewId));
    }
  }

  private double profileAverage() throws Exception {
    String json = mockMvc.perform(get("/api/users/{id}/profile", seller.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return objectMapper.readTree(json).path("data").path("averageRating").asDouble();
  }

  @Test
  void buyerEditsWithinWindowAndAggregateRecomputes() throws Exception {
    SellerReview review = seedReview(4, "Good, minor scuff");
    seedReview(5, "Perfect");
    assertThat(profileAverage()).isEqualTo(4.5);

    edit(buyerToken, review.getId(), editBody(2, "Actually, worse than described"), 200);

    SellerReview stored = reviewRepo.findById(review.getId()).orElseThrow();
    assertThat(stored.getRating()).isEqualTo(2);
    assertThat(stored.getBody()).isEqualTo("Actually, worse than described");
    assertThat(profileAverage()).isEqualTo(3.5);
  }

  @Test
  void editFourteenMinutesOldSucceeds() throws Exception {
    SellerReview review = seedReview(4, "Good, minor scuff");
    backdate(review, Duration.ofMinutes(14));

    edit(buyerToken, review.getId(), editBody(5, "On reflection, perfect"), 200);
    assertThat(reviewRepo.findById(review.getId()).orElseThrow().getRating())
        .isEqualTo(5);
  }

  @Test
  void nonBuyerGets403AndReviewUnchanged() throws Exception {
    SellerReview review = seedReview(4, "Good, minor scuff");

    edit(sellerToken, review.getId(), editBody(1, "Seller rewriting"), 403);
    edit(otherToken, review.getId(), editBody(1, "Stranger rewriting"), 403);

    SellerReview stored = reviewRepo.findById(review.getId()).orElseThrow();
    assertThat(stored.getRating()).isEqualTo(4);
    assertThat(stored.getBody()).isEqualTo("Good, minor scuff");
  }

  @Test
  void anonymousGets401AndUnknownGets404() throws Exception {
    SellerReview review = seedReview(4, "Good, minor scuff");

    mockMvc.perform(put("/api/reviews/{id}", review.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(editBody(5, "No token")))
        .andExpect(status().isUnauthorized());
    edit(buyerToken, 999999L, editBody(5, "No such review"), 404);
  }

  @Test
  void invalidRatingOrBodyGets400() throws Exception {
    SellerReview review = seedReview(4, "Good, minor scuff");

    edit(buyerToken, review.getId(), editBody(6, "Too many stars"), 400);
    edit(buyerToken, review.getId(), editBody(null, "No rating"), 400);
    edit(buyerToken, review.getId(), editBody(5, "x".repeat(501)), 400);
    assertThat(reviewRepo.findById(review.getId()).orElseThrow().getRating())
        .isEqualTo(4);
  }

  @Test
  void editSixteenMinutesOldGets422AndRowUnchanged() throws Exception {
    SellerReview review = seedReview(4, "Good, minor scuff");
    backdate(review, Duration.ofMinutes(16));

    edit(buyerToken, review.getId(), editBody(5, "Too late"), 422);

    SellerReview stored = reviewRepo.findById(review.getId()).orElseThrow();
    assertThat(stored.getRating()).isEqualTo(4);
    assertThat(stored.getBody()).isEqualTo("Good, minor scuff");
  }

  @Test
  void editAfterSellerResponseGets422AndResponseSurvives() throws Exception {
    SellerReview review = seedReview(4, "Good, minor scuff");

    mockMvc.perform(put("/api/reviews/{id}/response", review.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"response\":\"Thanks for trading!\"}"))
        .andExpect(status().isOk());

    edit(buyerToken, review.getId(), editBody(1, "Rewritten after the reply"), 422);

    SellerReview stored = reviewRepo.findById(review.getId()).orElseThrow();
    assertThat(stored.getRating()).isEqualTo(4);
    assertThat(stored.getBody()).isEqualTo("Good, minor scuff");
    assertThat(stored.getSellerResponse()).isEqualTo("Thanks for trading!");
  }

  @Test
  void responseRendersUnderReviewEditedBeforeIt() throws Exception {
    SellerReview review = seedReview(4, "Good, minor scuff");

    edit(buyerToken, review.getId(), editBody(5, "On reflection, perfect"), 200);
    mockMvc.perform(put("/api/reviews/{id}/response", review.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"response\":\"Glad it worked out!\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/users/{id}/reviews", seller.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].rating").value(5))
        .andExpect(jsonPath("$.data.content[0].body").value("On reflection, perfect"))
        .andExpect(jsonPath("$.data.content[0].sellerResponse")
            .value("Glad it worked out!"));
    assertThat(profileAverage()).isEqualTo(5.0);
  }
}
