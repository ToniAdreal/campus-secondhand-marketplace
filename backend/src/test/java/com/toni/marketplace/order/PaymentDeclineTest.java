package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.RefreshTokenRepository;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.common.PaymentDeclinedException;
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
 * Declined-capture path for the mock PSP (backlog #92): passing the
 * documented mock-only test token {@code tok_decline} as the pay call's
 * {@code paymentToken} declines the capture. The decline answers 402 in
 * the {code,message,data} envelope, the order stays PENDING, the listing
 * stays RESERVED, and nothing is remembered under the order's PSP
 * idempotency key — a retry with a succeeding token under the same key
 * still captures.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link OrderPaymentTest}: each test owns its data via unique users and
 * wipes the touched tables in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentDeclineTest {

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
  void declinedPay_returns402Envelope_orderStaysPendingItemStaysReserved() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);
    long capturesBefore = payments.capturesIssued();

    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"paymentToken\":\"tok_decline\"}"))
        .andExpect(status().isPaymentRequired())
        .andExpect(jsonPath("$.code").value(402))
        .andExpect(jsonPath("$.message").value(
            org.hamcrest.Matchers.containsString("declined")));

    assertThat(orders.findById(orderId)).hasValueSatisfying(o -> {
      assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING);
      // The declined attempt must not persist the PSP capture key it
      // generated — the pay transaction rolled back with the decline.
      assertThat(o.getCaptureIdempotencyKey()).isNull();
    });
    assertThat(items.findById(item.getId())).hasValueSatisfying(i ->
        assertThat(i.getStatus()).isEqualTo(ItemStatus.RESERVED));
    assertThat(payments.capturesIssued()).isEqualTo(capturesBefore);
  }

  @Test
  void retryAfterDecline_withDefaultToken_succeedsAndCountsExactlyOneCapture() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);
    long capturesBefore = payments.capturesIssued();

    payWithTokenExpectDecline(buyer, orderId);
    assertThat(payments.capturesIssued()).isEqualTo(capturesBefore);

    // Retry with an empty body (the default succeeding token): the same
    // order-scoped PSP key the decline was attempted under must still be
    // usable, because the decline remembered nothing under it.
    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.status").value("PAID"));

    assertThat(payments.capturesIssued()).isEqualTo(capturesBefore + 1);
    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID));
  }

  private void payWithTokenExpectDecline(User payer, long orderId) throws Exception {
    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(payer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"paymentToken\":\"tok_decline\"}"))
        .andExpect(status().isPaymentRequired())
        .andExpect(jsonPath("$.code").value(402));
  }

  @Test
  void paymentService_declineRemembersNothingUnderTheKey() {
    Item item = new Item("Desk lamp", "a used desk lamp", 2500L, 99L);
    Order order = new Order(item, 7L, 2500L);
    String key = "cap_test_" + UUID.randomUUID().toString().replace("-", "");
    long capturesBefore = payments.capturesIssued();

    assertThatThrownBy(() -> payments.capture(order, key, PaymentService.DECLINE_TOKEN))
        .isInstanceOf(PaymentDeclinedException.class);
    assertThat(payments.capturesIssued()).isEqualTo(capturesBefore);

    PaymentService.CaptureResult first = payments.capture(order, key, null);
    assertThat(payments.capturesIssued()).isEqualTo(capturesBefore + 1);

    // The successful capture IS remembered: a replay under the same key
    // returns it without issuing a second logical capture.
    PaymentService.CaptureResult replay = payments.capture(order, key, "tok_other");
    assertThat(replay).isEqualTo(first);
    assertThat(payments.capturesIssued()).isEqualTo(capturesBefore + 1);
  }

  @Test
  void paymentService_nonDeclineTokensSucceed() {
    Item item = new Item("Desk lamp", "a used desk lamp", 2500L, 99L);
    Order order = new Order(item, 7L, 2500L);

    // Blank and arbitrary tokens are not the decline seam.
    assertThat(payments.capture(order, "cap_blank_" + UUID.randomUUID(), ""))
        .isNotNull();
    assertThat(payments.capture(order, "cap_ok_" + UUID.randomUUID(), "tok_ok"))
        .isNotNull();
  }
}
