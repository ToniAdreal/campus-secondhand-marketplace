package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.RefreshTokenRepository;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import java.util.EnumSet;
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
 * {@code POST /api/orders/{id}/complete} contract: the listing's seller (or
 * an ADMIN) confirms the handoff on a PAID order, moving it to COMPLETED
 * and flipping the listing RESERVED → SOLD.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link OrderCancelTest}: each test owns its data via unique users and
 * wipes the touched tables in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderCompleteTest {

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

  private void payOrder(User buyer, long orderId) throws Exception {
    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk());
  }

  private void assertItemStatusInListView(User viewer, String keyword, String expectedStatus) throws Exception {
    mockMvc.perform(get("/api/items").param("q", keyword)
            .header("Authorization", "Bearer " + token(viewer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].status").value(expectedStatus));
  }

  @Test
  void sellerCompletes_paidOrderIsCompletedAndListingSoldInListView() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);
    payOrder(buyer, orderId);
    assertItemStatusInListView(buyer, "Film camera", "RESERVED");

    mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.id").value(orderId))
        .andExpect(jsonPath("$.data.status").value("COMPLETED"));

    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.COMPLETED));
    assertThat(items.findById(item.getId())).hasValueSatisfying(i ->
        assertThat(i.getStatus()).isEqualTo(ItemStatus.SOLD));
    // The list-view projection (single JPQL SELECT) reflects the flip.
    assertItemStatusInListView(buyer, "Film camera", "SOLD");
    assertThat(orders.findAll()).hasSize(1);
  }

  @Test
  void recompleteOfCompletedOrder_isIdempotent() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);
    payOrder(buyer, orderId);

    mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(orderId))
        .andExpect(jsonPath("$.data.status").value("COMPLETED"));

    assertThat(orders.findAll()).hasSize(1);
    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.COMPLETED));
    assertThat(items.findById(item.getId())).hasValueSatisfying(i ->
        assertThat(i.getStatus()).isEqualTo(ItemStatus.SOLD));
  }

  @Test
  void adminCompletes_paidOrder_succeeds() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    User admin = newUser("admin");
    admin.setRoles(EnumSet.of(Role.ADMIN));
    users.save(admin);
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);
    payOrder(buyer, orderId);

    mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("COMPLETED"));

    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.COMPLETED));
  }

  @Test
  void completeListingAlreadyMarkedSoldBySeller_leavesStatusAlone() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);
    payOrder(buyer, orderId);
    // Seller hand-marked the listing SOLD through PATCH
    // /api/items/{id}/status before completing the order.
    mockMvc.perform(patch("/api/items/{id}/status", item.getId())
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"SOLD\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("COMPLETED"));

    // The flip guard does not touch a listing that is already SOLD.
    assertThat(items.findById(item.getId())).hasValueSatisfying(i ->
        assertThat(i.getStatus()).isEqualTo(ItemStatus.SOLD));
  }

  @Test
  void buyerCompletes_isRejected403_orderStaysPaid() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);
    payOrder(buyer, orderId);

    mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID));
    assertThat(items.findById(item.getId())).hasValueSatisfying(i ->
        assertThat(i.getStatus()).isEqualTo(ItemStatus.RESERVED));
  }

  @Test
  void intruderCompletes_isRejected403() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    User intruder = newUser("intruder");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);
    payOrder(buyer, orderId);

    mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(intruder)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID));
  }

  @Test
  void sellerCompletesPendingOrder_isRejected422() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);

    mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));

    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING));
    assertThat(items.findById(item.getId())).hasValueSatisfying(i ->
        assertThat(i.getStatus()).isEqualTo(ItemStatus.RESERVED));
  }

  @Test
  void sellerCompletesCancelledOrder_isRejected422() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);
    mockMvc.perform(post("/api/orders/{id}/cancel", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));

    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELLED));
  }

  @Test
  void anonymousComplete_isRejected401() throws Exception {
    mockMvc.perform(post("/api/orders/{id}/complete", 12345L))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void completeUnknownOrder_is404() throws Exception {
    User seller = newUser("seller");

    mockMvc.perform(post("/api/orders/{id}/complete", 987654321L)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }
}
