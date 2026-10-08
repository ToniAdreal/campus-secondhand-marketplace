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
 * Order-scoped PSP idempotency keys (backlog #65 — implements #45's declared
 * follow-up): {@link PaymentService#capture(Order, String)} and
 * {@link PaymentService#refund(Order, String)} take a key that
 * {@code OrderService} generates once per order transition and stores on the
 * order row. A retry after a crash between the PSP call and the commit
 * reuses the stored key, and the mock replays the stored result instead of
 * issuing a second logical capture/refund.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link OrderRefundTest}: each test owns its data via unique users and
 * wipes the touched tables in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentIdempotencyTest {

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

  private void pay(User buyer, long orderId) throws Exception {
    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PAID"));
  }

  /**
   * The key is generated once per order, persisted on the row, and stable
   * across the idempotent re-pay — a crash-and-retry after the commit would
   * hand the PSP seam the same key, not a fresh one.
   */
  @Test
  void captureKey_generatedOnceAndStableAcrossRetries() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);

    pay(buyer, orderId);
    String key = orders.findById(orderId)
        .map(Order::getCaptureIdempotencyKey)
        .orElseThrow();
    assertThat(key).startsWith("cap_");

    // The idempotent re-pay returns the order without a new capture: the
    // stored key is untouched.
    pay(buyer, orderId);
    assertThat(orders.findById(orderId))
        .hasValueSatisfying(o -> assertThat(o.getCaptureIdempotencyKey()).isEqualTo(key));
  }

  /**
   * The refund key is a different key from the capture key, generated once
   * and persisted on the row.
   */
  @Test
  void refundKey_distinctFromCaptureKeyAndStable() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);
    pay(buyer, orderId);

    mockMvc.perform(post("/api/orders/{id}/refund", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("REFUNDED"));

    assertThat(orders.findById(orderId)).hasValueSatisfying(o -> {
      assertThat(o.getRefundIdempotencyKey()).startsWith("rfd_");
      assertThat(o.getRefundIdempotencyKey()).isNotEqualTo(o.getCaptureIdempotencyKey());
    });
  }

  /**
   * Simulates a retry after a crash between the PSP capture and the commit:
   * the same key is presented twice. The mock replays the first result — no
   * second logical capture is issued (the only observable counter moves
   * once).
   */
  @Test
  void mockReplay_sameCaptureKeyNeverIssuesTwice() {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    Order order = orders.save(new Order(item, buyer.getId(), 88000L));
    String key = "cap-" + UUID.randomUUID();

    long before = payments.capturesIssued();
    PaymentService.CaptureResult first = payments.capture(order, key);
    PaymentService.CaptureResult replay = payments.capture(order, key);

    assertThat(replay.captureId()).isEqualTo(first.captureId());
    assertThat(payments.capturesIssued() - before).isEqualTo(1);
  }

  /**
   * Same guarantee for the refund seam: a retried refund with the same key
   * replays, never double-refunds.
   */
  @Test
  void mockReplay_sameRefundKeyNeverIssuesTwice() {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    Order order = orders.save(new Order(item, buyer.getId(), 88000L));
    String key = "rfd-" + UUID.randomUUID();

    long before = payments.refundsIssued();
    PaymentService.RefundResult first = payments.refund(order, key);
    PaymentService.RefundResult replay = payments.refund(order, key);

    assertThat(replay.refundId()).isEqualTo(first.refundId());
    assertThat(payments.refundsIssued() - before).isEqualTo(1);
  }

  /**
   * Captures and refunds live in separate key namespaces: the same key
   * string presented to capture and to refund is recorded independently.
   */
  @Test
  void captureAndRefundKeys_liveInSeparateNamespaces() {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    Order order = orders.save(new Order(item, buyer.getId(), 88000L));
    String shared = "shared-" + UUID.randomUUID();

    long capturesBefore = payments.capturesIssued();
    long refundsBefore = payments.refundsIssued();
    payments.capture(order, shared);
    payments.refund(order, shared);

    assertThat(payments.capturesIssued() - capturesBefore).isEqualTo(1);
    assertThat(payments.refundsIssued() - refundsBefore).isEqualTo(1);
  }

  /**
   * A blank key is a programming error, not a network retry: fail fast at
   * the seam instead of silently skipping idempotency.
   */
  @Test
  void blankKey_isRejected() {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    Order order = orders.save(new Order(item, buyer.getId(), 88000L));

    assertThatThrownBy(() -> payments.capture(order, "  "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> payments.refund(order, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
