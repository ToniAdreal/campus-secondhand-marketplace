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
 * Refund reason (backlog #239): a direct refund without a reason is a
 * 400, the reason is stored on a REFUNDED payment-attempt row (not on
 * the capture rows, which keep a null reason), and it surfaces in the
 * ADMIN attempt viewer and on the order receipt. Dispute-resolved
 * refunds are out of scope — their note is backlog #240.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link OrderRefundTest}: each test owns its data via unique users
 * and wipes the touched tables in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderRefundReasonTest {

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
  private PaymentService payments;

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

  private void payOrder(User buyer, long orderId) throws Exception {
    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PAID"));
  }

  private void refundWithBody(User seller, long orderId, String body) throws Exception {
    mockMvc.perform(post("/api/orders/{id}/refund", orderId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk());
  }

  @Test
  void refundWithoutReason_is400_orderStaysPaid() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    long orderId = createOrder(buyer, newItem(seller));
    payOrder(buyer, orderId);
    long before = payments.refundsIssued();

    // Absent reason (empty body) and blank reason both 400.
    mockMvc.perform(post("/api/orders/{id}/refund", orderId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
    mockMvc.perform(post("/api/orders/{id}/refund", orderId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"   \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    assertThat(payments.refundsIssued() - before).isZero();
    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID));
    assertThat(attempts.countByOrderIdAndOutcome(orderId, PaymentAttempt.Outcome.REFUNDED))
        .isZero();
  }

  @Test
  void refundReasonOver500_is400_noPspCall() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    long orderId = createOrder(buyer, newItem(seller));
    payOrder(buyer, orderId);
    long before = payments.refundsIssued();

    mockMvc.perform(post("/api/orders/{id}/refund", orderId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"" + "x".repeat(501) + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    assertThat(payments.refundsIssued() - before).isZero();
    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID));
  }

  @Test
  void refundReason_persistsAndRendersInViewerAndReceipt() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    User admin = newAdmin();
    long orderId = createOrder(buyer, newItem(seller));
    payOrder(buyer, orderId);

    refundWithBody(seller, orderId, "{\"reason\":\"Item arrived damaged\"}");

    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.REFUNDED));
    assertThat(attempts.findByOrderIdOrderByCreatedAtDescIdDesc(orderId))
        .filteredOn(a -> a.getOutcome() == PaymentAttempt.Outcome.REFUNDED)
        .singleElement()
        .satisfies(a -> assertThat(a.getRefundReason()).isEqualTo("Item arrived damaged"));

    mockMvc.perform(get("/api/admin/payments/attempts")
            .param("outcome", "REFUNDED")
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(1))
        .andExpect(jsonPath("$.data.content[0].orderId").value(orderId))
        .andExpect(jsonPath("$.data.content[0].outcome").value("REFUNDED"))
        .andExpect(jsonPath("$.data.content[0].refundReason").value("Item arrived damaged"));

    mockMvc.perform(get("/api/orders/{id}/receipt", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("REFUNDED"))
        .andExpect(jsonPath("$.data.refundReason").value("Item arrived damaged"));
  }

  @Test
  void captureRows_keepNullReason() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    User admin = newAdmin();
    long orderId = createOrder(buyer, newItem(seller));
    payOrder(buyer, orderId);

    mockMvc.perform(get("/api/admin/payments/attempts")
            .param("outcome", "SUCCEEDED")
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(1))
        .andExpect(jsonPath("$.data.content[0].refundReason").isEmpty());
  }

  @Test
  void partialRefund_reasonAndAmountBothRecorded_latestReasonOnReceipt() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    long orderId = createOrder(buyer, newItem(seller));
    payOrder(buyer, orderId);

    refundWithBody(seller, orderId, "{\"amountCents\":30000,\"reason\":\"Missing accessory\"}");
    assertThat(orders.findById(orderId)).hasValueSatisfying(o -> {
      assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
      assertThat(o.getRefundedAmountCents()).isEqualTo(30_000L);
    });
    assertThat(attempts.findByOrderIdOrderByCreatedAtDescIdDesc(orderId))
        .filteredOn(a -> a.getOutcome() == PaymentAttempt.Outcome.REFUNDED)
        .singleElement()
        .satisfies(a -> assertThat(a.getRefundReason()).isEqualTo("Missing accessory"));

    refundWithBody(seller, orderId, "{\"reason\":\"Rest of the order refunded\"}");
    assertThat(orders.findById(orderId)).hasValueSatisfying(o -> {
      assertThat(o.getStatus()).isEqualTo(OrderStatus.REFUNDED);
      assertThat(o.getRefundedAmountCents()).isEqualTo(88_000L);
    });
    // The receipt shows the latest direct-refund reason.
    mockMvc.perform(get("/api/orders/{id}/receipt", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.refundReason").value("Rest of the order refunded"));
  }

  @Test
  void reRefundWithoutReason_is400_noSecondPspCall() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    long orderId = createOrder(buyer, newItem(seller));
    payOrder(buyer, orderId);
    refundWithBody(seller, orderId, "{\"reason\":\"Item arrived damaged\"}");
    long afterFirst = payments.refundsIssued();

    // Reason validation runs before the idempotent re-refund return.
    mockMvc.perform(post("/api/orders/{id}/refund", orderId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    assertThat(payments.refundsIssued() - afterFirst).isZero();
    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.REFUNDED));
  }
}
