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
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Persisted mock-PSP references (backlog #114): {@code POST /pay} stores
 * the {@code cap_mock_*} capture id on the order row and exposes it on
 * {@code OrderDto}; {@code POST /refund} likewise stores and exposes the
 * {@code rfd_mock_*} refund id. An idempotent re-pay / re-refund returns
 * the stored id byte-identical — never a fresh one — and a declined
 * capture (#92) stores nothing.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link OrderPaymentTest}: each test owns its data via unique users and
 * wipes the touched tables in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderPspReferenceTest {

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

  @Autowired
  private PaymentService payments;

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

  private Item newItem(User seller) {
    Item item = new Item("Film camera", "Canon AE-1", 88000L, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    return items.save(item);
  }

  private String createOrderBody(User buyer, Item item) throws Exception {
    return mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", "key-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + "}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
  }

  private long createOrder(User buyer, Item item) throws Exception {
    return ((Number) JsonPath.read(createOrderBody(buyer, item), "$.data.id")).longValue();
  }

  private String payBody(User buyer, long orderId) throws Exception {
    return mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
  }

  private String refundBody(User seller, long orderId) throws Exception {
    return mockMvc.perform(post("/api/orders/{id}/refund", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
  }

  @Test
  void freshOrder_exposesNullPspReferences() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);

    String body = createOrderBody(buyer, item);

    assertThat(JsonPath.<Object>read(body, "$.data.captureId")).isNull();
    assertThat(JsonPath.<Object>read(body, "$.data.refundId")).isNull();
    long orderId = ((Number) JsonPath.read(body, "$.data.id")).longValue();
    assertThat(orders.findById(orderId)).hasValueSatisfying(o -> {
      assertThat(o.getCaptureId()).isNull();
      assertThat(o.getRefundId()).isNull();
    });
  }

  @Test
  void pay_exposesTheStoredCaptureId_andNoRefundIdYet() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);

    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PAID"))
        .andExpect(jsonPath("$.data.captureId").value(Matchers.startsWith("cap_mock_")))
        .andExpect(jsonPath("$.data.refundId").value(Matchers.nullValue()));

    String stored = orders.findById(orderId).orElseThrow().getCaptureId();
    assertThat(stored).startsWith("cap_mock_");

    // The same reference is readable back through GET /api/orders/{id}.
    mockMvc.perform(get("/api/orders/{id}", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.captureId").value(stored));
  }

  @Test
  void rePay_keepsTheStoredCaptureIdByteIdentical() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);
    long capturesBefore = payments.capturesIssued();

    String first = JsonPath.read(payBody(buyer, orderId), "$.data.captureId");
    String second = JsonPath.read(payBody(buyer, orderId), "$.data.captureId");

    assertThat(second).isEqualTo(first);
    assertThat(orders.findById(orderId).orElseThrow().getCaptureId()).isEqualTo(first);
    // No second logical capture was issued for the idempotent re-pay.
    assertThat(payments.capturesIssued()).isEqualTo(capturesBefore + 1);
  }

  @Test
  void refund_addsTheRefundId_andKeepsTheCaptureId() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);
    String captureId = JsonPath.read(payBody(buyer, orderId), "$.data.captureId");
    long refundsBefore = payments.refundsIssued();

    String body = refundBody(seller, orderId);
    String refundId = JsonPath.read(body, "$.data.refundId");

    assertThat(refundId).startsWith("rfd_mock_");
    assertThat(JsonPath.<String>read(body, "$.data.captureId")).isEqualTo(captureId);
    assertThat(JsonPath.<String>read(body, "$.data.status")).isEqualTo("REFUNDED");
    assertThat(orders.findById(orderId)).hasValueSatisfying(o -> {
      assertThat(o.getCaptureId()).isEqualTo(captureId);
      assertThat(o.getRefundId()).isEqualTo(refundId);
    });

    // Re-refund is idempotent: same stored refund id, no second refund.
    String again = refundBody(seller, orderId);
    assertThat(JsonPath.<String>read(again, "$.data.refundId")).isEqualTo(refundId);
    assertThat(payments.refundsIssued()).isEqualTo(refundsBefore + 1);
  }

  @Test
  void declinedCapture_storesNothing_thenSuccessfulRetryStoresItsCaptureId() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);

    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"paymentToken\":\"tok_decline\"}"))
        .andExpect(status().isPaymentRequired())
        .andExpect(jsonPath("$.code").value(402));

    assertThat(orders.findById(orderId)).hasValueSatisfying(o -> {
      assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING);
      assertThat(o.getCaptureId()).isNull();
    });
    mockMvc.perform(get("/api/orders/{id}", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.captureId").value(Matchers.nullValue()));

    String captureId = JsonPath.read(payBody(buyer, orderId), "$.data.captureId");
    assertThat(captureId).startsWith("cap_mock_");
    assertThat(orders.findById(orderId).orElseThrow().getCaptureId()).isEqualTo(captureId);
  }
}
