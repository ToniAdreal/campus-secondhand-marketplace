package com.toni.marketplace.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Post-completion seller reviews (backlog #175): POST
 * /api/orders/{id}/review is buyer-only, COMPLETED-only, one review
 * per trade (409 on the second); GET /api/users/{id}/reviews is the
 * paginated newest-first list behind the seller profile, and the
 * profile's aggregate is computed from the rows (seeded 5/4/1 ratings
 * average to 10/3 — a stored average could not be caught drifting any
 * other way). Unknown or disabled sellers are 404 on the list, exactly
 * like the #174 profile.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SellerReviewTest {

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
    String tag = "rv-" + SEQ.incrementAndGet() + "-"
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

  private Order seedOrder(OrderStatus status) {
    Item item = new Item("Reviewed thing " + UUID.randomUUID().toString().substring(0, 8),
        "seeded", 5000L, seller.getId());
    item.setStatus(ItemStatus.SOLD);
    itemRepo.save(item);
    Order order = new Order(item, buyer.getId(), 5000L);
    order.setStatus(status);
    return orderRepo.save(order);
  }

  private String reviewBody(int rating, String body) throws Exception {
    var node = objectMapper.createObjectNode();
    node.put("rating", rating);
    if (body != null) {
      node.put("body", body);
    }
    return objectMapper.writeValueAsString(node);
  }

  @Test
  void buyerReviewsACompletedOrderAndTheRowIsStored() throws Exception {
    Order order = seedOrder(OrderStatus.COMPLETED);

    mockMvc.perform(post("/api/orders/{id}/review", order.getId())
            .header("Authorization", "Bearer " + buyerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(reviewBody(5, "Great seller, fast handover")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.orderId").value(order.getId()))
        .andExpect(jsonPath("$.data.buyerId").value(buyer.getId()))
        .andExpect(jsonPath("$.data.reviewerUsername").value(buyer.getUsername()))
        .andExpect(jsonPath("$.data.sellerId").value(seller.getId()))
        .andExpect(jsonPath("$.data.rating").value(5))
        .andExpect(jsonPath("$.data.body").value("Great seller, fast handover"));

    assertThat(reviewRepo.findByOrderId(order.getId())).isPresent()
        .get().extracting(SellerReview::getSellerId).isEqualTo(seller.getId());
  }

  @Test
  void starOnlyReviewIsCompleteAndBlankTextNormalisesToNull() throws Exception {
    Order order = seedOrder(OrderStatus.COMPLETED);

    mockMvc.perform(post("/api/orders/{id}/review", order.getId())
            .header("Authorization", "Bearer " + buyerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(reviewBody(4, "   ")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.rating").value(4))
        .andExpect(jsonPath("$.data.body").value(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  void onlyTheBuyerCanReview() throws Exception {
    Order order = seedOrder(OrderStatus.COMPLETED);

    mockMvc.perform(post("/api/orders/{id}/review", order.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(reviewBody(5, null)))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/api/orders/{id}/review", order.getId())
            .header("Authorization", "Bearer " + otherToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(reviewBody(5, null)))
        .andExpect(status().isForbidden());
    assertThat(reviewRepo.findByOrderId(order.getId())).isEmpty();
  }

  @Test
  void nonCompletedOrdersAre422() throws Exception {
    for (OrderStatus status : new OrderStatus[] {
        OrderStatus.PENDING, OrderStatus.PAID, OrderStatus.CANCELLED,
        OrderStatus.REFUNDED, OrderStatus.DISPUTED}) {
      Order order = seedOrder(status);
      mockMvc.perform(post("/api/orders/{id}/review", order.getId())
              .header("Authorization", "Bearer " + buyerToken)
              .contentType(MediaType.APPLICATION_JSON)
              .content(reviewBody(5, null)))
          .andExpect(status().isUnprocessableEntity());
    }
  }

  @Test
  void secondReviewOfTheSameOrderIs409() throws Exception {
    Order order = seedOrder(OrderStatus.COMPLETED);
    mockMvc.perform(post("/api/orders/{id}/review", order.getId())
            .header("Authorization", "Bearer " + buyerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(reviewBody(5, null)))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/orders/{id}/review", order.getId())
            .header("Authorization", "Bearer " + buyerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(reviewBody(1, "changed my mind")))
        .andExpect(status().isConflict());
    assertThat(reviewRepo.findByOrderId(order.getId())).isPresent()
        .get().extracting(SellerReview::getRating).isEqualTo(5);
  }

  @Test
  void ratingAndTextValidationIs400() throws Exception {
    Order order = seedOrder(OrderStatus.COMPLETED);
    for (String bad : new String[] {
        "{\"rating\":0}", "{\"rating\":6}", "{}",
        "{\"rating\":5,\"body\":\"" + "x".repeat(501) + "\"}"}) {
      mockMvc.perform(post("/api/orders/{id}/review", order.getId())
              .header("Authorization", "Bearer " + buyerToken)
              .contentType(MediaType.APPLICATION_JSON)
              .content(bad))
          .andExpect(status().isBadRequest());
    }
    assertThat(reviewRepo.findByOrderId(order.getId())).isEmpty();
  }

  @Test
  void unknownOrderIs404AndAnonymousIs401() throws Exception {
    mockMvc.perform(post("/api/orders/{id}/review", 999_999_999L)
            .header("Authorization", "Bearer " + buyerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(reviewBody(5, null)))
        .andExpect(status().isNotFound());
    Order order = seedOrder(OrderStatus.COMPLETED);
    mockMvc.perform(post("/api/orders/{id}/review", order.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(reviewBody(5, null)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void reviewListIsNewestFirstAndProfileAggregateIsComputedFromTheRows()
      throws Exception {
    // Three completed trades reviewed 5, 4, 1 — average 10/3.
    for (int rating : new int[] {5, 4, 1}) {
      Order order = seedOrder(OrderStatus.COMPLETED);
      mockMvc.perform(post("/api/orders/{id}/review", order.getId())
              .header("Authorization", "Bearer " + buyerToken)
              .contentType(MediaType.APPLICATION_JSON)
              .content(reviewBody(rating, "rated " + rating)))
          .andExpect(status().isOk());
    }

    MvcResult list = mockMvc.perform(get("/api/users/{id}/reviews", seller.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(3))
        .andReturn();
    JsonNode content = objectMapper
        .readTree(list.getResponse().getContentAsString()).path("data").path("content");
    // Newest first: the last-written review (rating 1) leads.
    assertThat(content.get(0).path("rating").asInt()).isEqualTo(1);
    assertThat(content.get(0).path("reviewerUsername").asText())
        .isEqualTo(buyer.getUsername());
    assertThat(content.get(2).path("rating").asInt()).isEqualTo(5);

    MvcResult profile = mockMvc.perform(get("/api/users/{id}/profile", seller.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.reviewCount").value(3))
        .andReturn();
    double average = objectMapper
        .readTree(profile.getResponse().getContentAsString())
        .path("data").path("averageRating").asDouble();
    assertThat(average).isCloseTo(10.0 / 3.0, org.assertj.core.data.Offset.offset(0.001));
  }

  @Test
  void sellerWithNoReviewsHasZeroCountAndNullAverage() throws Exception {
    mockMvc.perform(get("/api/users/{id}/profile", seller.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.reviewCount").value(0))
        .andExpect(jsonPath("$.data.averageRating").value(org.hamcrest.Matchers.nullValue()));
    mockMvc.perform(get("/api/users/{id}/reviews", seller.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(0));
  }

  @Test
  void unknownOrDisabledSellerReviewListIs404AndAnonymousIs401() throws Exception {
    mockMvc.perform(get("/api/users/{id}/reviews", 999_999_999L)
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/users/{id}/reviews", seller.getId()))
        .andExpect(status().isUnauthorized());

    seller.setDisabled(true);
    users.save(seller);
    mockMvc.perform(get("/api/users/{id}/reviews", seller.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isNotFound());
  }
}
