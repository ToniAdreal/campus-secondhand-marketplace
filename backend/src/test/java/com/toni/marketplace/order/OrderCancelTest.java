package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.RefreshTokenRepository;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
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
 * {@code POST /api/orders/{id}/cancel} contract: buyer-only cancellation of
 * PENDING orders, releasing the listing back to AVAILABLE.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link OrderPaymentTest}: each test owns its data via unique users and
 * wipes the touched tables in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderCancelTest {

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
  private IdempotencyKeyRepository keys;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @AfterEach
  void cleanUp() {
    keys.deleteAll();
    orders.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User newUser(String role) {
    String tag = role + "-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private Item newItem(User seller, ItemStatus status) {
    Item item = new Item("Film camera", "Canon AE-1", 88000L, seller.getId());
    item.setStatus(status);
    return items.save(item);
  }

  private long createOrder(User buyer, Item item) throws Exception {
    String body = mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", "key-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + "}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return ((Number) JsonPath.read(body, "$.data.id")).longValue();
  }

  private void assertItemStatusInListView(User viewer, String keyword, String expectedStatus) throws Exception {
    mockMvc.perform(get("/api/items").param("q", keyword)
            .header("Authorization", "Bearer " + token(viewer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].status").value(expectedStatus));
  }

  @Test
  void buyerCancels_pendingOrderIsCancelledAndListingReleasedToListView() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);
    assertItemStatusInListView(buyer, "Film camera", "RESERVED");

    mockMvc.perform(post("/api/orders/{id}/cancel", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.id").value(orderId))
        .andExpect(jsonPath("$.data.status").value("CANCELLED"));

    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELLED));
    assertThat(items.findById(item.getId())).hasValueSatisfying(i ->
        assertThat(i.getStatus()).isEqualTo(ItemStatus.AVAILABLE));
    // The list-view projection (single JPQL SELECT) reflects the release.
    assertItemStatusInListView(buyer, "Film camera", "AVAILABLE");
    assertThat(orders.findAll()).hasSize(1);
  }

  @Test
  void cancelReleasesActiveOrderGuard_buyerCanOrderTheItemAgain() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);

    mockMvc.perform(post("/api/orders/{id}/cancel", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk());

    // No active order remains: the uq_order_active_item guard released, and
    // the listing is AVAILABLE, so a fresh order is accepted.
    long secondOrderId = createOrder(buyer, item);
    assertThat(secondOrderId).isNotEqualTo(orderId);
    assertThat(orders.findById(secondOrderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING));
  }

  @Test
  void recancelOfCancelledOrder_isIdempotent() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);

    mockMvc.perform(post("/api/orders/{id}/cancel", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/orders/{id}/cancel", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(orderId))
        .andExpect(jsonPath("$.data.status").value("CANCELLED"));

    assertThat(orders.findAll()).hasSize(1);
    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELLED));
    assertThat(items.findById(item.getId())).hasValueSatisfying(i ->
        assertThat(i.getStatus()).isEqualTo(ItemStatus.AVAILABLE));
  }

  @Test
  void cancelPaidOrder_isRejected422_orderStaysPaidAndReserved() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);
    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/orders/{id}/cancel", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));

    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID));
    assertThat(items.findById(item.getId())).hasValueSatisfying(i ->
        assertThat(i.getStatus()).isEqualTo(ItemStatus.RESERVED));
  }

  @Test
  void cancelCompletedOrder_isRejected422() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);
    orders.findById(orderId).ifPresent(o -> {
      o.setStatus(OrderStatus.COMPLETED);
      orders.save(o);
    });

    mockMvc.perform(post("/api/orders/{id}/cancel", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));

    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.COMPLETED));
  }

  @Test
  void nonBuyerCancel_isRejected403_orderStaysPending() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    User intruder = newUser("intruder");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);

    mockMvc.perform(post("/api/orders/{id}/cancel", orderId)
            .header("Authorization", "Bearer " + token(intruder)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING));
    assertThat(items.findById(item.getId())).hasValueSatisfying(i ->
        assertThat(i.getStatus()).isEqualTo(ItemStatus.RESERVED));
  }

  @Test
  void sellerCancel_isRejected403() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);

    mockMvc.perform(post("/api/orders/{id}/cancel", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
  }

  @Test
  void anonymousCancel_isRejected401() throws Exception {
    mockMvc.perform(post("/api/orders/{id}/cancel", 12345L))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void cancelUnknownOrder_is404() throws Exception {
    User buyer = newUser("buyer");

    mockMvc.perform(post("/api/orders/{id}/cancel", 987654321L)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }
}
