package com.toni.marketplace.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seller responses on reviews (backlog #246): PUT
 * /api/reviews/{id}/response is seller-only (403 for the buyer and
 * for a stranger, 401 anonymous), unknown review 404, blank or
 * overlong response 400; a second PUT overwrites the single column
 * (still one row, still one response); the response renders under
 * its review in GET /api/users/{id}/reviews; and the profile
 * aggregate (average + count) is identical before and after a
 * response, because it reads the rating column alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SellerReviewResponseTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private ItemRepository itemRepo;
  @Autowired private OrderRepository orderRepo;
  @Autowired private SellerReviewRepository reviewRepo;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;
  @Autowired private ObjectMapper objectMapper;

  private User seller;
  private User buyer;
  private String buyerToken;
  private String sellerToken;
  private String otherToken;

  @BeforeEach
  void createUsers() {
    String tag = "rvr-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    seller = users.save(new User(tag + "-seller", tag + "-seller@example.com",
        passwords.encode("seller-password")));
    buyer = users.save(new User(tag + "-buyer", tag + "-buyer@example.com",
        passwords.encode("buyer-password")));
    User other = users.save(new User(tag + "-other", tag + "-other@example.com",
        passwords.encode("other-password")));
    buyerToken = jwt.createTokenPair(buyer).accessToken();
    sellerToken = jwt.createTokenPair(seller).accessToken();
    otherToken = jwt.createTokenPair(other).accessToken();
  }

  private SellerReview seedReview(int rating, String body) {
    // seller_review.order_id carries a real FK (V40), so the review
    // needs a real completed order — the response path itself loads
    // the review row only, but the seed must satisfy the constraint.
    Item item = new Item("Response thing " + UUID.randomUUID().toString().substring(0, 8),
        "seeded", 5000L, seller.getId());
    item.setStatus(ItemStatus.SOLD);
    itemRepo.save(item);
    Order order = new Order(item, buyer.getId(), 5000L);
    order.setStatus(OrderStatus.COMPLETED);
    orderRepo.save(order);
    return reviewRepo.save(new SellerReview(order.getId(),
        buyer.getId(), seller.getId(), rating, body));
  }

  private String responseBody(String response) throws Exception {
    var node = objectMapper.createObjectNode();
    if (response != null) {
      node.put("response", response);
    }
    return objectMapper.writeValueAsString(node);
  }

  @Test
  void sellerRespondsAndTheResponseRendersUnderItsReview() throws Exception {
    SellerReview review = seedReview(4, "Fast handover, item as described");

    mockMvc.perform(put("/api/reviews/{id}/response", review.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(responseBody("Thanks for the kind words — happy trading!")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(review.getId()))
        .andExpect(jsonPath("$.data.sellerResponse")
            .value("Thanks for the kind words — happy trading!"))
        .andExpect(jsonPath("$.data.rating").value(4));

    mockMvc.perform(get("/api/users/{id}/reviews", seller.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].sellerResponse")
            .value("Thanks for the kind words — happy trading!"));
  }

  @Test
  void sellerEditsTheResponseInPlaceWithoutASecondRow() throws Exception {
    SellerReview review = seedReview(5, "Great seller");

    mockMvc.perform(put("/api/reviews/{id}/response", review.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(responseBody("First reply")))
        .andExpect(status().isOk());
    mockMvc.perform(put("/api/reviews/{id}/response", review.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(responseBody("Edited reply")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sellerResponse").value("Edited reply"));

    assertThat(reviewRepo.findAll()).hasSize(1);
    assertThat(reviewRepo.findById(review.getId())).isPresent()
        .get().extracting(SellerReview::getSellerResponse).isEqualTo("Edited reply");
  }

  @Test
  void buyerAndStrangerGet403AndTheResponseIsUntouched() throws Exception {
    SellerReview review = seedReview(3, "Slow replies");

    mockMvc.perform(put("/api/reviews/{id}/response", review.getId())
            .header("Authorization", "Bearer " + buyerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(responseBody("buyer trying to answer")))
        .andExpect(status().isForbidden());
    mockMvc.perform(put("/api/reviews/{id}/response", review.getId())
            .header("Authorization", "Bearer " + otherToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(responseBody("stranger trying to answer")))
        .andExpect(status().isForbidden());

    assertThat(reviewRepo.findById(review.getId())).isPresent()
        .get().extracting(SellerReview::getSellerResponse).isNull();
  }

  @Test
  void unknownReviewIs404AndAnonymousIs401() throws Exception {
    mockMvc.perform(put("/api/reviews/{id}/response", 999999999L)
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(responseBody("No such review")))
        .andExpect(status().isNotFound());

    SellerReview review = seedReview(5, "Great seller");
    mockMvc.perform(put("/api/reviews/{id}/response", review.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(responseBody("No credential")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void blankAndOverlongResponsesAre400() throws Exception {
    SellerReview review = seedReview(5, "Great seller");

    mockMvc.perform(put("/api/reviews/{id}/response", review.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(responseBody("   ")))
        .andExpect(status().isBadRequest());
    mockMvc.perform(put("/api/reviews/{id}/response", review.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(responseBody("x".repeat(1001))))
        .andExpect(status().isBadRequest());

    assertThat(reviewRepo.findById(review.getId())).isPresent()
        .get().extracting(SellerReview::getSellerResponse).isNull();
  }

  @Test
  void responseIsTrimmedAtWriteTime() throws Exception {
    SellerReview review = seedReview(5, "Great seller");

    mockMvc.perform(put("/api/reviews/{id}/response", review.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(responseBody("  Thanks!  ")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sellerResponse").value("Thanks!"));
  }

  @Test
  void aggregateIsUnchangedByResponses() throws Exception {
    SellerReview first = seedReview(5, "Excellent");
    seedReview(3, "Average");

    mockMvc.perform(get("/api/users/{id}/profile", seller.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.reviewCount").value(2))
        .andExpect(jsonPath("$.data.averageRating").value(4.0));

    mockMvc.perform(put("/api/reviews/{id}/response", first.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(responseBody("Thanks for the 5 stars!")))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/users/{id}/profile", seller.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.reviewCount").value(2))
        .andExpect(jsonPath("$.data.averageRating").value(4.0));
  }
}
