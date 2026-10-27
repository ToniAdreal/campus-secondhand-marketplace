package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;
import java.util.TreeSet;
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
 * {@code GET /api/orders/{id}/receipt} contract (backlog #180).
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link OrderReadTest}: each test owns its data via unique users and
 * wipes the touched tables in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderReceiptTest {

  private static final AtomicLong SEQ = new AtomicLong();
  private static final Set<String> RECEIPT_KEYS = Set.of(
      "orderId", "status", "paid", "itemId", "itemTitle", "amountCents",
      "formattedPrice", "buyerUsername", "sellerUsername", "createdAt",
      "paidAt", "completedAt", "captureId", "refundId", "refundReason");

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

  @Autowired
  private ObjectMapper objectMapper;

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

  /** Pays and returns the pay response body (carries the handover code). */
  private String pay(User buyer, long orderId) throws Exception {
    return mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
  }

  private Set<String> dataKeys(String body) throws Exception {
    JsonNode data = objectMapper.readTree(body).get("data");
    Set<String> keys = new TreeSet<>();
    data.fieldNames().forEachRemaining(keys::add);
    return keys;
  }

  @Test
  void pendingReceipt_buyerOk_markedUnpaid_exactKeySet() throws Exception {
    User seller = newUser("seller", false);
    User buyer = newUser("buyer", false);
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);

    String body = mockMvc.perform(get("/api/orders/{id}/receipt", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.orderId").value(orderId))
        .andExpect(jsonPath("$.data.status").value("PENDING"))
        .andExpect(jsonPath("$.data.paid").value(false))
        .andExpect(jsonPath("$.data.itemId").value(item.getId()))
        .andExpect(jsonPath("$.data.itemTitle").value("Film camera"))
        .andExpect(jsonPath("$.data.amountCents").value(88000))
        .andExpect(jsonPath("$.data.formattedPrice").value("¥880.00"))
        .andExpect(jsonPath("$.data.buyerUsername").value(buyer.getUsername()))
        .andExpect(jsonPath("$.data.sellerUsername").value(seller.getUsername()))
        .andExpect(jsonPath("$.data.paidAt").isEmpty())
        .andExpect(jsonPath("$.data.completedAt").isEmpty())
        .andExpect(jsonPath("$.data.captureId").isEmpty())
        .andExpect(jsonPath("$.data.refundId").isEmpty())
        .andReturn().getResponse().getContentAsString();

    // The exact key set is the leak pin: no idempotency keys, no
    // handover hash, no version, no PSP internals can ever appear.
    assertThat(dataKeys(body)).isEqualTo(RECEIPT_KEYS);
  }

  @Test
  void receipt_sellerAndAdminCanRead() throws Exception {
    User seller = newUser("seller", false);
    User buyer = newUser("buyer", false);
    User admin = newUser("admin", true);
    long orderId = createOrder(buyer, newItem(seller));

    mockMvc.perform(get("/api/orders/{id}/receipt", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.orderId").value(orderId));

    mockMvc.perform(get("/api/orders/{id}/receipt", orderId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.orderId").value(orderId));
  }

  @Test
  void receipt_strangerGets404_noExistenceLeak() throws Exception {
    User seller = newUser("seller", false);
    User buyer = newUser("buyer", false);
    User stranger = newUser("stranger", false);
    long orderId = createOrder(buyer, newItem(seller));

    // Same status + code as an unknown id — nothing distinguishes them.
    mockMvc.perform(get("/api/orders/{id}/receipt", orderId)
            .header("Authorization", "Bearer " + token(stranger)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));

    mockMvc.perform(get("/api/orders/{id}/receipt", 987654321L)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  void receipt_anonymous401() throws Exception {
    mockMvc.perform(get("/api/orders/{id}/receipt", 1L))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void paidReceipt_hasPaidAtAndCaptureId() throws Exception {
    User seller = newUser("seller", false);
    User buyer = newUser("buyer", false);
    long orderId = createOrder(buyer, newItem(seller));
    pay(buyer, orderId);

    mockMvc.perform(get("/api/orders/{id}/receipt", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PAID"))
        .andExpect(jsonPath("$.data.paid").value(true))
        .andExpect(jsonPath("$.data.paidAt").isNotEmpty())
        .andExpect(jsonPath("$.data.captureId").isNotEmpty())
        .andExpect(jsonPath("$.data.completedAt").isEmpty())
        .andExpect(jsonPath("$.data.refundId").isEmpty());
  }

  @Test
  void completedReceipt_hasCompletedAt() throws Exception {
    User seller = newUser("seller", false);
    User buyer = newUser("buyer", false);
    long orderId = createOrder(buyer, newItem(seller));
    String payBody = pay(buyer, orderId);
    String handoverCode = JsonPath.read(payBody, "$.data.handoverCode");

    mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"handoverCode\":\"" + handoverCode + "\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/orders/{id}/receipt", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("COMPLETED"))
        .andExpect(jsonPath("$.data.paid").value(true))
        .andExpect(jsonPath("$.data.paidAt").isNotEmpty())
        .andExpect(jsonPath("$.data.completedAt").isNotEmpty());
  }

  @Test
  void refundedReceipt_hasRefundIdAndCaptureId() throws Exception {
    User seller = newUser("seller", false);
    User buyer = newUser("buyer", false);
    long orderId = createOrder(buyer, newItem(seller));
    pay(buyer, orderId);

    mockMvc.perform(post("/api/orders/{id}/refund", orderId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"item damaged\"}")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/orders/{id}/receipt", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("REFUNDED"))
        .andExpect(jsonPath("$.data.paid").value(true))
        .andExpect(jsonPath("$.data.captureId").isNotEmpty())
        .andExpect(jsonPath("$.data.refundId").isNotEmpty());
  }
}
