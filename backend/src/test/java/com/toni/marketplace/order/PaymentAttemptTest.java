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
import java.lang.reflect.Field;
import java.util.Arrays;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Durable payment-attempt ledger (backlog #211): every capture attempt
 * against an order leaves a {@code payment_attempt} row — DECLINED rows
 * survive the pay transaction's rollback (the recorder's REQUIRES_NEW
 * write), SUCCEEDED rows commit with the pay, an idempotent re-pay
 * writes nothing new, and the payment token appears in no stored column
 * and in neither DTO's key set. Buyer reads of a still-PENDING order
 * whose latest attempt declined carry the generic failure note; the
 * ADMIN viewer lists the rows.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link PaymentDeclineTest}: the rollback-survival pin is only
 * meaningful when the pay really commits/rolls back its own
 * transaction. Each test owns its data via unique users and wipes the
 * touched tables in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentAttemptTest {

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

  @Autowired
  private JdbcTemplate jdbc;

  @AfterEach
  void cleanUp() {
    attempts.deleteAll();
    keys.deleteAll();
    orders.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User newUser(String role) {
    String tag = role + "-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
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

  private void payExpectDecline(User buyer, long orderId) throws Exception {
    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"paymentToken\":\"" + PaymentService.DECLINE_TOKEN + "\"}"))
        .andExpect(status().isPaymentRequired())
        .andExpect(jsonPath("$.code").value(402));
  }

  private void payExpectOk(User buyer, long orderId) throws Exception {
    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PAID"));
  }

  @Test
  void declinedPay_attemptRowExistsAfterRollback() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    long orderId = createOrder(buyer, newItem(seller));

    payExpectDecline(buyer, orderId);

    // The recorder pin: the pay transaction rolled back (the order is
    // still PENDING and its capture key was never persisted), yet the
    // DECLINED attempt row is durable.
    assertThat(orders.findById(orderId)).hasValueSatisfying(o -> {
      assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING);
      assertThat(o.getCaptureIdempotencyKey()).isNull();
    });
    List<PaymentAttempt> rows = attempts.findByOrderIdOrderByCreatedAtDescIdDesc(orderId);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getOutcome()).isEqualTo(PaymentAttempt.Outcome.DECLINED);
    assertThat(rows.get(0).getIdempotencyKey()).startsWith("cap_");
    assertThat(rows.get(0).getCreatedAt()).isNotNull();
  }

  @Test
  void successfulPay_writesExactlyOneSucceededRow() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    long orderId = createOrder(buyer, newItem(seller));

    payExpectOk(buyer, orderId);

    List<PaymentAttempt> rows = attempts.findByOrderIdOrderByCreatedAtDescIdDesc(orderId);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getOutcome()).isEqualTo(PaymentAttempt.Outcome.SUCCEEDED);
    // The attempt ran under the order-scoped key persisted on the row.
    String storedKey = orders.findById(orderId).orElseThrow().getCaptureIdempotencyKey();
    assertThat(storedKey).isNotNull();
    assertThat(rows.get(0).getIdempotencyKey()).isEqualTo(storedKey);
  }

  @Test
  void idempotentDoublePayReplay_writesNoSecondRow() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    long orderId = createOrder(buyer, newItem(seller));

    payExpectOk(buyer, orderId);
    // Idempotent re-pay of the already-PAID order: returns the order,
    // captures nothing, and writes NO new attempt row.
    payExpectOk(buyer, orderId);

    assertThat(attempts.findByOrderIdOrderByCreatedAtDescIdDesc(orderId)).hasSize(1);
    assertThat(attempts.countByOrderIdAndOutcome(orderId, PaymentAttempt.Outcome.SUCCEEDED))
        .isEqualTo(1);
  }

  @Test
  void declineThenSuccess_ledgerHoldsBothAttemptsNewestFirst() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    long orderId = createOrder(buyer, newItem(seller));

    payExpectDecline(buyer, orderId);
    payExpectOk(buyer, orderId);

    List<PaymentAttempt> rows = attempts.findByOrderIdOrderByCreatedAtDescIdDesc(orderId);
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).getOutcome()).isEqualTo(PaymentAttempt.Outcome.SUCCEEDED);
    assertThat(rows.get(1).getOutcome()).isEqualTo(PaymentAttempt.Outcome.DECLINED);
    // Each attempt carries the key its own pay transaction generated:
    // the declined attempt's key rolled back with its transaction (the
    // order row never persisted it — the #92 pin), so the retry minted
    // a fresh one. The declined key survives ONLY in this ledger row.
    assertThat(rows.get(0).getIdempotencyKey()).startsWith("cap_");
    assertThat(rows.get(1).getIdempotencyKey()).startsWith("cap_");
    assertThat(rows.get(0).getIdempotencyKey())
        .isNotEqualTo(rows.get(1).getIdempotencyKey());
  }

  @Test
  void tokenHygiene_noStoredColumnOrDtoKeyCarriesTheToken() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    long orderId = createOrder(buyer, newItem(seller));
    payExpectDecline(buyer, orderId);

    // Schema pin: the entity has no field that could hold a token.
    List<String> entityFields = Arrays.stream(PaymentAttempt.class.getDeclaredFields())
        .map(Field::getName)
        .toList();
    assertThat(entityFields)
        .containsExactlyInAnyOrder("id", "orderId", "outcome", "idempotencyKey",
            "refundReason", "createdAt");
    assertThat(entityFields).noneMatch(name -> name.toLowerCase().contains("token"));

    // DTO key-set pin: the ADMIN projection exposes no token either.
    List<String> dtoKeys = Arrays.stream(PaymentAttemptDto.class.getRecordComponents())
        .map(c -> c.getName())
        .toList();
    assertThat(dtoKeys)
        .containsExactlyInAnyOrder("id", "orderId", "outcome", "idempotencyKey",
            "refundReason", "createdAt");
    assertThat(dtoKeys).noneMatch(name -> name.toLowerCase().contains("token"));

    // Value pin: no stored column value equals the decline token.
    List<String> storedValues = jdbc.queryForList(
        "select cast(id as varchar) v from payment_attempt where order_id = ? "
            + "union all select outcome from payment_attempt where order_id = ? "
            + "union all select idempotency_key from payment_attempt where order_id = ?",
        String.class, orderId, orderId, orderId);
    assertThat(storedValues).isNotEmpty();
    assertThat(storedValues).doesNotContain(PaymentService.DECLINE_TOKEN);
  }

  @Test
  void adminAttempts_adminSeesDeclinedAttempt_nonAdmin403_anonymous401() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    User admin = newAdmin();
    long orderId = createOrder(buyer, newItem(seller));
    payExpectDecline(buyer, orderId);

    mockMvc.perform(get("/api/admin/payments/attempts")
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.content.length()").value(1))
        .andExpect(jsonPath("$.data.content[0].orderId").value(orderId))
        .andExpect(jsonPath("$.data.content[0].outcome").value("DECLINED"))
        .andExpect(jsonPath("$.data.content[0].idempotencyKey").exists())
        .andExpect(jsonPath("$.data.content[0].createdAt").exists())
        // Field-set pin: no token-shaped field can leak.
        .andExpect(jsonPath("$.data.content[0].paymentToken").doesNotExist())
        .andExpect(jsonPath("$.data.content[0].token").doesNotExist());

    // The outcome filter binds to the enum: DECLINED narrows, SUCCEEDED empties.
    mockMvc.perform(get("/api/admin/payments/attempts")
            .param("outcome", "DECLINED")
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(1));
    mockMvc.perform(get("/api/admin/payments/attempts")
            .param("outcome", "SUCCEEDED")
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(0));
    mockMvc.perform(get("/api/admin/payments/attempts")
            .param("outcome", "NOPE")
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isBadRequest());

    mockMvc.perform(get("/api/admin/payments/attempts")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    mockMvc.perform(get("/api/admin/payments/attempts"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void buyerRead_afterDeclineShowsNote_afterSuccessfulPayNoteIsGone() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    User admin = newAdmin();
    long orderId = createOrder(buyer, newItem(seller));

    // No attempt yet: no note key at all (NON_NULL drops it).
    mockMvc.perform(get("/api/orders/{id}", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.lastPaymentFailure").doesNotExist());

    payExpectDecline(buyer, orderId);

    // Single-order buyer read carries the generic note — and nothing
    // PSP-internal alongside it.
    mockMvc.perform(get("/api/orders/{id}", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PENDING"))
        .andExpect(jsonPath("$.data.lastPaymentFailure")
            .value(OrderService.LAST_PAYMENT_FAILURE_NOTE));

    // Buyer list carries it too.
    mockMvc.perform(get("/api/orders")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].lastPaymentFailure")
            .value(OrderService.LAST_PAYMENT_FAILURE_NOTE));

    // Seller and ADMIN reads stay note-free.
    mockMvc.perform(get("/api/seller/orders/{id}", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.lastPaymentFailure").doesNotExist());
    mockMvc.perform(get("/api/orders/{id}", orderId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.lastPaymentFailure").doesNotExist());

    // After a later successful pay the order is PAID and the note is gone.
    payExpectOk(buyer, orderId);
    mockMvc.perform(get("/api/orders/{id}", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PAID"))
        .andExpect(jsonPath("$.data.lastPaymentFailure").doesNotExist());
  }
}
