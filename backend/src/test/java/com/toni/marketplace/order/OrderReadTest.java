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
import com.toni.marketplace.item.ItemStatus;
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
 * {@code GET /api/orders} and {@code GET /api/orders/{id}} contract.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link OrderPaymentTest}: each test owns its data via unique users and
 * wipes the touched tables in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderReadTest {

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
  void buyerListsOwnOrders_newestFirst() throws Exception {
    User seller = newUser("seller", false);
    User buyer = newUser("buyer", false);
    long first = createOrder(buyer, newItem(seller));
    long second = createOrder(buyer, newItem(seller));
    long third = createOrder(buyer, newItem(seller));

    String body = mockMvc.perform(get("/api/orders")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.totalElements").value(3))
        .andExpect(jsonPath("$.data.content.length()").value(3))
        .andReturn().getResponse().getContentAsString();

    List<Number> ids = JsonPath.read(body, "$.data.content[*].id");
    assertThat(ids.stream().map(Number::longValue))
        .containsExactly(third, second, first);
  }

  @Test
  void list_isIsolatedBetweenBuyers() throws Exception {
    User seller = newUser("seller", false);
    User alice = newUser("alice", false);
    User bob = newUser("bob", false);
    createOrder(alice, newItem(seller));
    createOrder(alice, newItem(seller));
    createOrder(bob, newItem(seller));

    String body = mockMvc.perform(get("/api/orders")
            .header("Authorization", "Bearer " + token(alice)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(2))
        .andReturn().getResponse().getContentAsString();

    List<Number> buyerIds = JsonPath.read(body, "$.data.content[*].buyerId");
    assertThat(buyerIds).hasSize(2);
    assertThat(buyerIds.stream().map(Number::longValue))
        .containsOnly(alice.getId());
  }

  @Test
  void list_paginationEnvelope() throws Exception {
    User seller = newUser("seller", false);
    User buyer = newUser("buyer", false);
    for (int i = 0; i < 5; i++) {
      createOrder(buyer, newItem(seller));
    }

    mockMvc.perform(get("/api/orders")
            .param("size", "2")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(5))
        .andExpect(jsonPath("$.data.totalPages").value(3))
        .andExpect(jsonPath("$.data.number").value(0))
        .andExpect(jsonPath("$.data.size").value(2))
        .andExpect(jsonPath("$.data.content.length()").value(2));

    // Last page carries the remainder, still newest-first ordering.
    mockMvc.perform(get("/api/orders")
            .param("size", "2")
            .param("page", "2")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.number").value(2))
        .andExpect(jsonPath("$.data.content.length()").value(1))
        .andExpect(jsonPath("$.data.last").value(true));
  }

  @Test
  void getOrderById_buyerOk() throws Exception {
    User seller = newUser("seller", false);
    User buyer = newUser("buyer", false);
    long orderId = createOrder(buyer, newItem(seller));

    mockMvc.perform(get("/api/orders/{id}", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.id").value(orderId))
        .andExpect(jsonPath("$.data.buyerId").value(buyer.getId()))
        .andExpect(jsonPath("$.data.status").value("PENDING"))
        .andExpect(jsonPath("$.data.amountCents").value(88000));
  }

  @Test
  void getOrderById_crossBuyer_isRejected403() throws Exception {
    User seller = newUser("seller", false);
    User alice = newUser("alice", false);
    User bob = newUser("bob", false);
    long orderId = createOrder(alice, newItem(seller));

    mockMvc.perform(get("/api/orders/{id}", orderId)
            .header("Authorization", "Bearer " + token(bob)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    // The 403 leaves the order untouched for its real buyer.
    mockMvc.perform(get("/api/orders/{id}", orderId)
            .header("Authorization", "Bearer " + token(alice)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(orderId));
  }

  @Test
  void getOrderById_adminCanReadAnyOrder() throws Exception {
    User seller = newUser("seller", false);
    User buyer = newUser("buyer", false);
    User admin = newUser("admin", true);
    long orderId = createOrder(buyer, newItem(seller));

    mockMvc.perform(get("/api/orders/{id}", orderId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(orderId))
        .andExpect(jsonPath("$.data.buyerId").value(buyer.getId()));
  }

  @Test
  void getOrderById_unknownOrder_is404() throws Exception {
    User buyer = newUser("buyer", false);

    mockMvc.perform(get("/api/orders/{id}", 987654321L)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  void anonymousListAndGet_areRejected401() throws Exception {
    mockMvc.perform(get("/api/orders"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    mockMvc.perform(get("/api/orders/{id}", 1L))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }
}
