package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.audit.AuditAction;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * Backlog #179 — the buyer handover confirmation code: pay mints a
 * 6-digit code (hash-only on the row, plaintext disclosed exactly once
 * in the pay response), a seller completion must present it, failures
 * are the identical 400 and lock the code after 5 attempts, the buyer
 * can regenerate (invalidating the old code), and an ADMIN completion
 * is an audited override.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link OrderCompleteTest}: each test owns its data via unique users
 * and wipes the touched tables in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderHandoverCodeTest {

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
  private AuditLogRepository auditLog;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @AfterEach
  void cleanUp() {
    auditLog.deleteAll();
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

  private User newAdmin() {
    User admin = newUser("admin");
    admin.setRoles(EnumSet.of(Role.ADMIN));
    return users.save(admin);
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private Item newItem(User seller) {
    Item item = new Item("Film camera", "Canon AE-1", 88000L, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
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

  /** Pays as the buyer; returns the handover code disclosed in the pay response. */
  private String payOrder(User buyer, long orderId) throws Exception {
    String body = mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.handoverCode").isString())
        .andReturn().getResponse().getContentAsString();
    String code = JsonPath.read(body, "$.data.handoverCode");
    assertThat(code).matches("\\d{6}");
    return code;
  }

  private MvcResult complete(User caller, long orderId, String code) throws Exception {
    var request = post("/api/orders/{id}/complete", orderId)
        .header("Authorization", "Bearer " + token(caller));
    if (code != null) {
      request = request.contentType(MediaType.APPLICATION_JSON)
          .content("{\"handoverCode\":\"" + code + "\"}");
    }
    return mockMvc.perform(request).andReturn();
  }

  private String regenerate(User buyer, long orderId) throws Exception {
    String body = mockMvc.perform(post("/api/orders/{id}/handover-code/regenerate", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return JsonPath.read(body, "$.data.handoverCode");
  }

  private String wrongCodeFor(String code) {
    return "000000".equals(code) ? "000001" : "000000";
  }

  @Test
  void payMintsCode_hashStored_sellerPayloadsHaveNoCodeField() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);
    String code = payOrder(buyer, orderId);

    // Only the hash is stored — never the plaintext.
    Order stored = orders.findById(orderId).orElseThrow();
    assertThat(stored.getHandoverCodeHash()).isNotNull();
    assertThat(stored.getHandoverCodeHash()).isNotEqualTo(code);
    assertThat(stored.getHandoverCodeHash()).hasSize(64);

    // Buyer detail read: the code is NOT re-disclosed (hash-only store),
    // so the field is absent rather than null.
    mockMvc.perform(get("/api/orders/{id}", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.handoverCode").doesNotExist())
        .andExpect(jsonPath("$.data.handoverCodeHash").doesNotExist());
    // Seller reads: no code field, no hash — the pinned asymmetry.
    mockMvc.perform(get("/api/seller/orders/{id}", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.handoverCode").doesNotExist())
        .andExpect(jsonPath("$.data.handoverCodeHash").doesNotExist());
    mockMvc.perform(get("/api/seller/orders")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].handoverCode").doesNotExist());
    // An idempotent re-pay does not re-mint or re-disclose either.
    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.handoverCode").doesNotExist());
  }

  @Test
  void sellerComplete_missingAndWrongCode_areTheIdentical400_orderStaysPaid()
      throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);
    String code = payOrder(buyer, orderId);

    MvcResult missing = complete(seller, orderId, null);
    assertThat(missing.getResponse().getStatus()).isEqualTo(400);
    String missingBody = missing.getResponse().getContentAsString();

    MvcResult wrong = complete(seller, orderId, wrongCodeFor(code));
    assertThat(wrong.getResponse().getStatus()).isEqualTo(400);
    String wrongBody = wrong.getResponse().getContentAsString();

    // Identical envelope: no oracle distinguishes missing from wrong.
    assertThat((String) JsonPath.read(wrongBody, "$.message"))
        .isEqualTo((String) JsonPath.read(missingBody, "$.message"));
    assertThat(orders.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.PAID);
    assertThat(items.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.RESERVED);
  }

  @Test
  void sellerComplete_correctCode_completesAndFlipsListingSold() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);
    String code = payOrder(buyer, orderId);

    MvcResult result = complete(seller, orderId, code);
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(orders.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.COMPLETED);
    assertThat(items.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.SOLD);
    // A seller completion is trailed as a code-verified completion.
    assertThat(auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(
        AuditTargetType.ORDER, orderId))
        .anyMatch(row -> row.getAction() == AuditAction.ORDER_COMPLETED);

    // Idempotent re-complete: no code demanded for the replay.
    MvcResult replay = complete(seller, orderId, null);
    assertThat(replay.getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  void sellerComplete_fiveWrongAttempts_lockTheCode_evenTheCorrectCodeGets400()
      throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);
    String code = payOrder(buyer, orderId);
    String wrong = wrongCodeFor(code);

    for (int i = 0; i < 5; i++) {
      assertThat(complete(seller, orderId, wrong).getResponse().getStatus())
          .isEqualTo(400);
    }
    // The failures were counted durably despite each 400 rolling back.
    Order stored = orders.findById(orderId).orElseThrow();
    assertThat(stored.getHandoverFailedAttempts()).isEqualTo(5);
    assertThat(stored.getHandoverLockedUntil()).isNotNull();
    assertThat(stored.getStatus()).isEqualTo(OrderStatus.PAID);

    // Locked: even the correct code is refused with the same 400.
    assertThat(complete(seller, orderId, code).getResponse().getStatus())
        .isEqualTo(400);
    assertThat(orders.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.PAID);

    // The buyer's recovery path is regeneration — it clears the lock.
    String fresh = regenerate(buyer, orderId);
    assertThat(complete(seller, orderId, fresh).getResponse().getStatus())
        .isEqualTo(200);
  }

  @Test
  void buyerRegenerate_invalidatesOldCode_newCodeCompletes() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);
    String original = payOrder(buyer, orderId);

    String fresh = regenerate(buyer, orderId);
    while (fresh.equals(original)) {
      // 1-in-1,000,000 collision: mint again so the assertions below
      // distinguish old from new deterministically.
      fresh = regenerate(buyer, orderId);
    }
    assertThat(complete(seller, orderId, original).getResponse().getStatus())
        .isEqualTo(400);
    assertThat(complete(seller, orderId, fresh).getResponse().getStatus())
        .isEqualTo(200);
    assertThat(orders.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.COMPLETED);
  }

  @Test
  void regenerate_isBuyerOnly_andPaidOnly() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);

    // PENDING: no code exists yet to regenerate.
    mockMvc.perform(post("/api/orders/{id}/handover-code/regenerate", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));

    payOrder(buyer, orderId);
    // The seller cannot mint a code for their own completion.
    mockMvc.perform(post("/api/orders/{id}/handover-code/regenerate", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
    // Unknown order: 404.
    mockMvc.perform(post("/api/orders/{id}/handover-code/regenerate", 987654321L)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  void adminComplete_withoutCode_succeeds_andIsAuditedAsOverride() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    User admin = newAdmin();
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);
    payOrder(buyer, orderId);

    MvcResult result = complete(admin, orderId, null);
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(orders.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.COMPLETED);

    var rows = auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(
        AuditTargetType.ORDER, orderId);
    assertThat(rows.stream().filter(
        row -> row.getAction() == AuditAction.ORDER_COMPLETED_ADMIN_OVERRIDE
            && row.getActorUserId().equals(admin.getId())).count())
        .isEqualTo(1);
    assertThat(rows.stream().filter(
        row -> row.getAction() == AuditAction.ORDER_COMPLETED).count())
        .isZero();
  }
}
