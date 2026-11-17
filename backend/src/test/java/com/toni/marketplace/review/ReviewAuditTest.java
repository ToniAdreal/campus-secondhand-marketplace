package com.toni.marketplace.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditLog;
import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.audit.AuditTargetType;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.RefreshTokenRepository;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.order.Order;
import com.toni.marketplace.order.OrderRepository;
import com.toni.marketplace.order.OrderStatus;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Review-lifecycle audit rows (backlog #390): create, edit and respond
 * each write exactly one row to the append-only trail from inside the
 * transition's own transaction, with the acting buyer/seller as actor
 * and the review row as target. An identical re-response writes no
 * second row — the response is a single-column overwrite and the PUT
 * is idempotent by design. The ADMIN audit-log read picks the rows up
 * through its existing filters.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring {@link
 * com.toni.marketplace.offer.OfferAuditTest}: the rows must be committed
 * by the requests themselves to be asserted here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReviewAuditTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository items;

  @Autowired
  private OrderRepository orders;

  @Autowired
  private SellerReviewRepository reviews;

  @Autowired
  private AuditLogRepository auditLog;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @Autowired
  private ObjectMapper objectMapper;

  @AfterEach
  void cleanUp() {
    auditLog.deleteAll();
    reviews.deleteAll();
    orders.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User newUser(String role) {
    String tag = "review-audit-" + role + "-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private User newAdmin() {
    String tag = "review-audit-admin-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    User admin = new User(tag, tag + "@example.com", passwords.encode("password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    return users.save(admin);
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private Order seedCompletedOrder(User seller, User buyer) {
    Item item = new Item("Reviewed thing " + UUID.randomUUID().toString().substring(0, 8),
        "seeded", 5000L, seller.getId());
    item.setStatus(ItemStatus.SOLD);
    items.save(item);
    Order order = new Order(item, buyer.getId(), 5000L);
    order.setStatus(OrderStatus.COMPLETED);
    return orders.save(order);
  }

  private long createReview(User buyer, long orderId) throws Exception {
    var node = objectMapper.createObjectNode();
    node.put("rating", 5);
    node.put("body", "Great seller, fast handover");
    String body = mockMvc.perform(post("/api/orders/{id}/review", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(node)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return ((Number) JsonPath.read(body, "$.data.id")).longValue();
  }

  private void respond(User seller, long reviewId, String response) throws Exception {
    var node = objectMapper.createObjectNode();
    node.put("response", response);
    mockMvc.perform(put("/api/reviews/{id}/response", reviewId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(node)))
        .andExpect(status().isOk());
  }

  private List<AuditLog> rowsFor(long reviewId) {
    return auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(AuditTargetType.REVIEW, reviewId);
  }

  @Test
  void create_writesOneReviewCreatedRow_actorIsBuyer() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Order order = seedCompletedOrder(seller, buyer);

    long reviewId = createReview(buyer, order.getId());

    assertThat(rowsFor(reviewId)).singleElement().satisfies(row -> {
      assertThat(row.getAction()).isEqualTo(AuditAction.REVIEW_CREATED);
      assertThat(row.getActorUserId()).isEqualTo(buyer.getId());
      assertThat(row.getTargetType()).isEqualTo(AuditTargetType.REVIEW);
      assertThat(row.getTargetId()).isEqualTo(reviewId);
    });
  }

  @Test
  void edit_writesOneReviewEditedRow_actorIsBuyer() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Order order = seedCompletedOrder(seller, buyer);
    long reviewId = createReview(buyer, order.getId());

    var node = objectMapper.createObjectNode();
    node.put("rating", 4);
    node.put("body", "Good, minor scuff");
    mockMvc.perform(put("/api/reviews/{id}", reviewId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(node)))
        .andExpect(status().isOk());

    assertThat(rowsFor(reviewId)).hasSize(2);
    assertThat(rowsFor(reviewId).get(1)).satisfies(row -> {
      assertThat(row.getAction()).isEqualTo(AuditAction.REVIEW_EDITED);
      assertThat(row.getActorUserId()).isEqualTo(buyer.getId());
    });
  }

  @Test
  void respond_writesOneReviewRespondedRow_actorIsSeller() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Order order = seedCompletedOrder(seller, buyer);
    long reviewId = createReview(buyer, order.getId());

    respond(seller, reviewId, "Thanks for the kind words!");

    assertThat(rowsFor(reviewId)).hasSize(2);
    assertThat(rowsFor(reviewId).get(1)).satisfies(row -> {
      assertThat(row.getAction()).isEqualTo(AuditAction.REVIEW_RESPONDED);
      assertThat(row.getActorUserId()).isEqualTo(seller.getId());
    });
  }

  @Test
  void identicalReResponse_writesNoSecondRow() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Order order = seedCompletedOrder(seller, buyer);
    long reviewId = createReview(buyer, order.getId());

    respond(seller, reviewId, "Thanks for the kind words!");
    respond(seller, reviewId, "Thanks for the kind words!");

    assertThat(rowsFor(reviewId)).hasSize(2);
    assertThat(rowsFor(reviewId).get(1).getAction())
        .isEqualTo(AuditAction.REVIEW_RESPONDED);
  }

  @Test
  void changedResponse_writesASecondRow() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Order order = seedCompletedOrder(seller, buyer);
    long reviewId = createReview(buyer, order.getId());

    respond(seller, reviewId, "Thanks!");
    respond(seller, reviewId, "Thanks — happy trading!");

    assertThat(rowsFor(reviewId)).hasSize(3);
    assertThat(rowsFor(reviewId).get(2)).satisfies(row -> {
      assertThat(row.getAction()).isEqualTo(AuditAction.REVIEW_RESPONDED);
      assertThat(row.getActorUserId()).isEqualTo(seller.getId());
    });
  }

  @Test
  void adminAuditLogRead_returnsReviewRowsViaExistingFilters() throws Exception {
    User admin = newAdmin();
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Order order = seedCompletedOrder(seller, buyer);
    long reviewId = createReview(buyer, order.getId());

    mockMvc.perform(get("/api/admin/audit-log")
            .header("Authorization", "Bearer " + token(admin))
            .param("action", "REVIEW_CREATED")
            .param("targetType", "REVIEW")
            .param("targetId", String.valueOf(reviewId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].action").value("REVIEW_CREATED"))
        .andExpect(jsonPath("$.data.content[0].actorUserId").value(buyer.getId()))
        .andExpect(jsonPath("$.data.content[0].targetType").value("REVIEW"))
        .andExpect(jsonPath("$.data.content[0].targetId").value(reviewId));
  }
}
