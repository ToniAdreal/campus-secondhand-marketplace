package com.toni.marketplace.order;

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
 * {@code GET /api/orders/{id}/attempts} contract (backlog #335): the
 * buyer's own payment-attempt history, paginated newest first, reusing
 * the ADMIN viewer's {@link PaymentAttemptDto} as-is.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link OrderReadTest}: each test owns its data via unique users and
 * wipes the touched tables in {@code cleanUp()}. Attempt rows are
 * seeded directly through the repository — the read contract under test
 * is access + ordering + paging, and the write paths already pin row
 * creation in {@link PaymentAttemptTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderAttemptReadTest {

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
  private PaymentAttemptRepository attempts;

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
    attempts.deleteAll();
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

  /** Seeds DECLINED, then SUCCEEDED, then REFUNDED rows, in that order. */
  private void seedAttempts(long orderId) {
    attempts.save(new PaymentAttempt(orderId, PaymentAttempt.Outcome.DECLINED, "cap_seed_1"));
    attempts.save(new PaymentAttempt(orderId, PaymentAttempt.Outcome.SUCCEEDED, "cap_seed_1"));
    attempts.save(new PaymentAttempt(orderId, PaymentAttempt.Outcome.REFUNDED,
        "rfd_seed_1", "buyer asked twice"));
  }

  @Test
  void buyerReadsOwnAttempts_newestFirstPaginated() throws Exception {
    User seller = newUser("seller", false);
    User buyer = newUser("buyer", false);
    long orderId = createOrder(buyer, newItem(seller));
    seedAttempts(orderId);

    // First page (size 2): newest two rows, REFUNDED then SUCCEEDED.
    mockMvc.perform(get("/api/orders/{id}/attempts", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .param("size", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(2))
        .andExpect(jsonPath("$.data.content[0].outcome").value("REFUNDED"))
        .andExpect(jsonPath("$.data.content[0].refundReason").value("buyer asked twice"))
        .andExpect(jsonPath("$.data.content[1].outcome").value("SUCCEEDED"))
        .andExpect(jsonPath("$.data.totalElements").value(3))
        .andExpect(jsonPath("$.data.totalPages").value(2));

    // Second page: the oldest row, DECLINED, and nothing else.
    mockMvc.perform(get("/api/orders/{id}/attempts", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .param("size", "2")
            .param("page", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(1))
        .andExpect(jsonPath("$.data.content[0].outcome").value("DECLINED"));
  }

  @Test
  void anotherBuyerGets403() throws Exception {
    User seller = newUser("seller", false);
    User buyer = newUser("buyer", false);
    User other = newUser("other", false);
    long orderId = createOrder(buyer, newItem(seller));
    seedAttempts(orderId);

    mockMvc.perform(get("/api/orders/{id}/attempts", orderId)
            .header("Authorization", "Bearer " + token(other)))
        .andExpect(status().isForbidden());
  }

  @Test
  void sellerWithoutAdminGets403() throws Exception {
    User seller = newUser("seller", false);
    User buyer = newUser("buyer", false);
    long orderId = createOrder(buyer, newItem(seller));
    seedAttempts(orderId);

    // The seller's view stays the seller-orders surface: this buyer
    // endpoint must not leak the buyer's payment trail to them.
    mockMvc.perform(get("/api/orders/{id}/attempts", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminReadsAttempts() throws Exception {
    User seller = newUser("seller", false);
    User buyer = newUser("buyer", false);
    User admin = newUser("admin", true);
    long orderId = createOrder(buyer, newItem(seller));
    seedAttempts(orderId);

    mockMvc.perform(get("/api/orders/{id}/attempts", orderId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(3))
        .andExpect(jsonPath("$.data.content[0].outcome").value("REFUNDED"));
  }

  @Test
  void unknownOrderGets404() throws Exception {
    User buyer = newUser("buyer", false);

    mockMvc.perform(get("/api/orders/{id}/attempts", 987654321L)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isNotFound());
  }

  @Test
  void anonymousGets401() throws Exception {
    mockMvc.perform(get("/api/orders/{id}/attempts", 1L))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }
}
