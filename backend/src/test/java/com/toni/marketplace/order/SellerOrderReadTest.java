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
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
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
 * {@code GET /api/seller/orders} and {@code GET /api/seller/orders/{id}}
 * contract — the seller mirror of {@link OrderReadTest}.
 *
 * <p>Authz matrix pinned here:
 * <ul>
 *   <li>a seller lists only orders placed on their own listings;</li>
 *   <li>a user with no listings sees an empty seller list (200) — sellers
 *       are identified by listing ownership, not by a role;</li>
 *   <li>reading a single order on someone else's listing is 403;</li>
 *   <li>ADMIN may read any single order but the list stays scoped to the
 *       caller's own listings;</li>
 *   <li>unknown ids are 404; anonymous callers get the 401 envelope.</li>
 * </ul>
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link OrderPaymentTest}: each test owns its data via unique users and
 * wipes the touched tables in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SellerOrderReadTest {

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

  private User newUser(String tag, boolean admin) {
    String name = tag + "-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    User user = new User(name, name + "@example.com", passwords.encode("password"));
    if (admin) {
      user.setRoles(EnumSet.of(Role.ADMIN));
    }
    return users.save(user);
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private Item newItem(User seller) {
    return items.save(new Item("Film camera", "Canon AE-1", 88000L, seller.getId()));
  }

  /** Directly moves an order to a status so list filtering can be pinned
   *  without driving the whole pay/cancel lifecycle for each row. */
  private void setStatus(long orderId, OrderStatus status) {
    Order order = orders.findById(orderId).orElseThrow();
    order.setStatus(status);
    orders.save(order);
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

  @Test
  void sellerListsOrdersOnOwnListings_newestFirst() throws Exception {
    User seller = newUser("seller", false);
    User buyer = newUser("buyer", false);
    long first = createOrder(buyer, newItem(seller));
    long second = createOrder(buyer, newItem(seller));

    String body = mockMvc.perform(get("/api/seller/orders")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.totalElements").value(2))
        .andExpect(jsonPath("$.data.content.length()").value(2))
        .andReturn().getResponse().getContentAsString();

    List<Number> ids = JsonPath.read(body, "$.data.content[*].id");
    assertThat(ids.stream().map(Number::longValue))
        .containsExactly(second, first);
  }

  @Test
  void list_isScopedToOwnListingsOnly() throws Exception {
    User sellerA = newUser("sellerA", false);
    User sellerB = newUser("sellerB", false);
    User buyer = newUser("buyer", false);
    createOrder(buyer, newItem(sellerA));
    createOrder(buyer, newItem(sellerA));
    createOrder(buyer, newItem(sellerB));

    String body = mockMvc.perform(get("/api/seller/orders")
            .header("Authorization", "Bearer " + token(sellerA)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(2))
        .andReturn().getResponse().getContentAsString();

    List<Number> sellerIds = JsonPath.read(body, "$.data.content[*].itemId");
    assertThat(sellerIds).hasSize(2);
    // The order on sellerB's listing never leaks into sellerA's view.
    for (Number itemId : sellerIds) {
      assertThat(items.findById(itemId.longValue()))
          .isPresent()
          .get()
          .extracting(Item::getSellerId)
          .isEqualTo(sellerA.getId());
    }
  }

  @Test
  void list_paginationEnvelope() throws Exception {
    User seller = newUser("seller", false);
    User buyer = newUser("buyer", false);
    for (int i = 0; i < 3; i++) {
      createOrder(buyer, newItem(seller));
    }

    mockMvc.perform(get("/api/seller/orders")
            .param("size", "2")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(3))
        .andExpect(jsonPath("$.data.totalPages").value(2))
        .andExpect(jsonPath("$.data.content.length()").value(2));

    mockMvc.perform(get("/api/seller/orders")
            .param("size", "2")
            .param("page", "1")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.number").value(1))
        .andExpect(jsonPath("$.data.content.length()").value(1))
        .andExpect(jsonPath("$.data.last").value(true));
  }

  @Test
  void list_statusFilter_returnsOnlyThatStatusOnOwnListings() throws Exception {
    User seller = newUser("seller", false);
    User otherSeller = newUser("otherSeller", false);
    User buyer = newUser("buyer", false);
    createOrder(buyer, newItem(seller));
    long paid = createOrder(buyer, newItem(seller));
    setStatus(paid, OrderStatus.PAID);
    // A PAID order on someone else's listing must never leak in.
    setStatus(createOrder(buyer, newItem(otherSeller)), OrderStatus.PAID);

    String body = mockMvc.perform(get("/api/seller/orders")
            .param("status", "PAID")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content.length()").value(1))
        .andExpect(jsonPath("$.data.content[0].status").value("PAID"))
        .andReturn().getResponse().getContentAsString();
    List<Number> ids = JsonPath.read(body, "$.data.content[*].id");
    assertThat(ids.stream().map(Number::longValue)).containsExactly(paid);

    mockMvc.perform(get("/api/seller/orders")
            .param("status", "PENDING")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1));

    // Omitting the filter keeps the unfiltered behaviour.
    mockMvc.perform(get("/api/seller/orders")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(2));
  }

  @Test
  void list_statusFilter_noOrdersInStatus_isEmptyPageNotError() throws Exception {
    User seller = newUser("seller", false);
    User buyer = newUser("buyer", false);
    createOrder(buyer, newItem(seller));

    mockMvc.perform(get("/api/seller/orders")
            .param("status", "REFUNDED")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.totalElements").value(0))
        .andExpect(jsonPath("$.data.content.length()").value(0));
  }

  @Test
  void list_statusFilter_invalidStatus_is400() throws Exception {
    User seller = newUser("seller", false);

    mockMvc.perform(get("/api/seller/orders")
            .param("status", "bogus")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void list_statusFilter_paginationTotalsReflectFilteredSet() throws Exception {
    User seller = newUser("seller", false);
    User buyer = newUser("buyer", false);
    for (int i = 0; i < 3; i++) {
      setStatus(createOrder(buyer, newItem(seller)), OrderStatus.PAID);
    }
    createOrder(buyer, newItem(seller));

    mockMvc.perform(get("/api/seller/orders")
            .param("status", "PAID")
            .param("size", "2")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(3))
        .andExpect(jsonPath("$.data.totalPages").value(2))
        .andExpect(jsonPath("$.data.content.length()").value(2));
  }

  @Test
  void getOrderById_sellerOk() throws Exception {
    User seller = newUser("seller", false);
    User buyer = newUser("buyer", false);
    long orderId = createOrder(buyer, newItem(seller));

    mockMvc.perform(get("/api/seller/orders/{id}", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.id").value(orderId))
        .andExpect(jsonPath("$.data.buyerId").value(buyer.getId()))
        .andExpect(jsonPath("$.data.status").value("PENDING"))
        .andExpect(jsonPath("$.data.amountCents").value(88000));
  }

  @Test
  void getOrderById_orderOnOtherSellersListing_isRejected403() throws Exception {
    User sellerA = newUser("sellerA", false);
    User sellerB = newUser("sellerB", false);
    User buyer = newUser("buyer", false);
    long orderId = createOrder(buyer, newItem(sellerB));

    mockMvc.perform(get("/api/seller/orders/{id}", orderId)
            .header("Authorization", "Bearer " + token(sellerA)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    // The 403 leaves the order readable for its real seller.
    mockMvc.perform(get("/api/seller/orders/{id}", orderId)
            .header("Authorization", "Bearer " + token(sellerB)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(orderId));
  }

  @Test
  void sellerEndpoints_rejectNonSellerOnForeignOrder_403() throws Exception {
    User seller = newUser("seller", false);
    User buyer = newUser("buyer", false);
    long orderId = createOrder(buyer, newItem(seller));

    // A user with no listings sees an empty seller list (200), not 403 —
    // sellers are identified by listing ownership, not by role, so there is
    // no "buyer" principal to deny here.
    mockMvc.perform(get("/api/seller/orders")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(0));

    // ...but reading someone else's order via the seller path is 403.
    mockMvc.perform(get("/api/seller/orders/{id}", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
  }

  @Test
  void getOrderById_adminCanReadAnyOrder() throws Exception {
    User seller = newUser("seller", false);
    User buyer = newUser("buyer", false);
    User admin = newUser("admin", true);
    long orderId = createOrder(buyer, newItem(seller));

    mockMvc.perform(get("/api/seller/orders/{id}", orderId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(orderId))
        .andExpect(jsonPath("$.data.buyerId").value(buyer.getId()));
  }

  @Test
  void list_adminStaysScopedToOwnListings() throws Exception {
    User seller = newUser("seller", false);
    User buyer = newUser("buyer", false);
    User admin = newUser("admin", true);
    createOrder(buyer, newItem(seller));

    // The admin has no listings, so the seller list is empty for them even
    // though they could read any single order via GET /api/seller/orders/{id}.
    mockMvc.perform(get("/api/seller/orders")
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(0));
  }

  @Test
  void getOrderById_unknownOrder_is404() throws Exception {
    User seller = newUser("seller", false);

    mockMvc.perform(get("/api/seller/orders/{id}", 987654321L)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  void anonymousListAndGet_areRejected401() throws Exception {
    mockMvc.perform(get("/api/seller/orders"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    mockMvc.perform(get("/api/seller/orders/{id}", 1L))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }
}
