package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.RefreshTokenRepository;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.dispute.OrderDisputeRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.notification.NotificationRepository;
import java.lang.reflect.RecordComponent;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Order transition timestamps (backlog #418): Flyway V77
 * {@code cancelled_at} / {@code refunded_at} complete the set
 * {@code paidAt} / {@code completedAt} / {@code shippedAt} started by
 * #180/#222, and {@code OrderDto} now exposes all five (NON_NULL, so a
 * stamp the order never reached is absent from the payload, never a
 * null key). Each stamp is asserted at the row and at the DTO: cancel
 * (buyer path and the expiry path) stamps {@code cancelledAt}, every
 * accepted refund — full, partial, dispute-resolved — stamps
 * {@code refundedAt}, and idempotent replays never rewrite a stamp.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link OrderRefundTest}: each test owns its data via unique users and
 * wipes the touched tables in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderTransitionTimestampsTest {

  private static final AtomicLong SEQ = new AtomicLong();
  private static final long PRICE = 88_000L;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository items;

  @Autowired
  private OrderRepository orders;

  @Autowired
  private OrderDisputeRepository disputes;

  @Autowired
  private NotificationRepository notifications;

  @Autowired
  private AuditLogRepository auditLog;

  @Autowired
  private IdempotencyKeyRepository keys;

  @Autowired
  private PaymentAttemptRepository paymentAttempts;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @Autowired
  private OrderService orderService;

  @Autowired
  private JdbcTemplate jdbc;

  @AfterEach
  void cleanUp() {
    // Backlog #381: this class drives transitions that record
    // notification/audit/dispute rows, and those tables have no FKs —
    // without this wipe the rows survive into the next class in the
    // same fork and break its empty-table assertions.
    paymentAttempts.deleteAll();
    disputes.deleteAll();
    notifications.deleteAll();
    auditLog.deleteAll();
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
    Item item = new Item("Film camera", "Canon AE-1", PRICE, seller.getId());
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
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PAID"))
        .andExpect(jsonPath("$.data.paidAt").exists());
  }

  @Test
  void dtoKeySetPin_allFiveTransitionInstantsExposed() {
    // The record shape is the contract: a field added to the row but
    // forgotten on the DTO (or vice versa) fails here, not in a
    // consumer that silently reads null.
    List<String> keys = Arrays.stream(OrderDto.class.getRecordComponents())
        .map(RecordComponent::getName)
        .toList();
    assertThat(keys).containsExactlyInAnyOrder(
        "id", "itemId", "buyerId", "status", "amountCents", "createdAt",
        "captureId", "refundId", "refundedAmountCents", "handoverCode",
        "lastPaymentFailure", "carrier", "trackingNumber", "shippedAt",
        "paidAt", "completedAt", "cancelledAt", "refundedAt");
  }

  @Test
  void cancelStampsCancelledAt_dtoCarriesIt_onlyThatStamp() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);

    mockMvc.perform(post("/api/orders/{id}/cancel", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CANCELLED"))
        .andExpect(jsonPath("$.data.cancelledAt").exists())
        .andExpect(jsonPath("$.data.paidAt").doesNotExist())
        .andExpect(jsonPath("$.data.completedAt").doesNotExist())
        .andExpect(jsonPath("$.data.refundedAt").doesNotExist());

    assertThat(orders.findById(orderId)).hasValueSatisfying(o -> {
      assertThat(o.getCancelledAt()).isNotNull();
      assertThat(o.getPaidAt()).isNull();
      assertThat(o.getCompletedAt()).isNull();
      assertThat(o.getRefundedAt()).isNull();
    });
  }

  @Test
  void reCancelDoesNotRewriteCancelledAt() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);
    mockMvc.perform(post("/api/orders/{id}/cancel", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk());
    // Pin the stamp to a fixed instant: a replay that re-stamped would
    // move it to wall-clock now and fail the equality below. Truncated
    // to seconds so the comparison survives the DB column's precision.
    Instant pinned = Instant.now().minus(Duration.ofDays(2))
        .truncatedTo(ChronoUnit.SECONDS);
    jdbc.update("UPDATE orders SET cancelled_at = ? WHERE id = ?",
        Timestamp.from(pinned), orderId);

    mockMvc.perform(post("/api/orders/{id}/cancel", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CANCELLED"));

    assertThat(orders.findById(orderId).orElseThrow().getCancelledAt())
        .isEqualTo(pinned);
  }

  @Test
  void expiryStampsCancelledAt() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);

    assertThat(orderService.expireStaleOrder(orderId, Instant.now().plusSeconds(3600)))
        .isTrue();
    assertThat(orders.findById(orderId)).hasValueSatisfying(o -> {
      assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELLED);
      assertThat(o.getCancelledAt()).isNotNull();
    });
  }

  @Test
  void refundStampsRefundedAt_dtoCarriesIt() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);
    payOrder(buyer, orderId);

    mockMvc.perform(post("/api/orders/{id}/refund", orderId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"item damaged\"}")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("REFUNDED"))
        .andExpect(jsonPath("$.data.paidAt").exists())
        .andExpect(jsonPath("$.data.refundedAt").exists())
        .andExpect(jsonPath("$.data.completedAt").doesNotExist())
        .andExpect(jsonPath("$.data.cancelledAt").doesNotExist());

    assertThat(orders.findById(orderId)).hasValueSatisfying(o -> {
      assertThat(o.getPaidAt()).isNotNull();
      assertThat(o.getRefundedAt()).isNotNull();
    });
  }

  @Test
  void partialRefundStampsRefundedAt_orderStaysPaid() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);
    payOrder(buyer, orderId);

    // A partial refund moves money without completing the refund — the
    // pre-#418 gap was exactly that this left no timestamp at all.
    mockMvc.perform(post("/api/orders/{id}/refund", orderId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amountCents\":1000,\"reason\":\"small scratch\"}")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PAID"))
        .andExpect(jsonPath("$.data.refundedAmountCents").value(1000))
        .andExpect(jsonPath("$.data.refundedAt").exists());

    assertThat(orders.findById(orderId)).hasValueSatisfying(o -> {
      assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
      assertThat(o.getRefundedAmountCents()).isEqualTo(1000L);
      assertThat(o.getRefundedAt()).isNotNull();
    });
    assertThat(items.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.RESERVED);
  }

  @Test
  void disputeResolveRefundStampsRefundedAt() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    User admin = newAdmin();
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);
    payOrder(buyer, orderId);

    String openBody = mockMvc.perform(post("/api/orders/{id}/dispute", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"never arrived\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    long disputeId = ((Number) JsonPath.read(openBody, "$.data.id")).longValue();

    mockMvc.perform(post("/api/admin/disputes/{id}/resolve-refund", disputeId)
            .header("Authorization", "Bearer " + token(admin))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"resolutionNote\":\"Carrier confirms non-delivery.\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("RESOLVED_REFUND"));

    assertThat(orders.findById(orderId)).hasValueSatisfying(o -> {
      assertThat(o.getStatus()).isEqualTo(OrderStatus.REFUNDED);
      assertThat(o.getRefundedAt()).isNotNull();
    });
  }

  @Test
  void paidOrderDtoJson_showsPaidAt_hidesUnreachedStamps() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = createOrder(buyer, item);
    payOrder(buyer, orderId);

    // NON_NULL is the contract: stamps the order never reached are
    // absent keys, not nulls a consumer could mistake for "recorded".
    mockMvc.perform(get("/api/orders/{id}", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.paidAt").exists())
        .andExpect(jsonPath("$.data.completedAt").doesNotExist())
        .andExpect(jsonPath("$.data.cancelledAt").doesNotExist())
        .andExpect(jsonPath("$.data.refundedAt").doesNotExist());
  }
}
