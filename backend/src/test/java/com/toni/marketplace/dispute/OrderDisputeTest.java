package com.toni.marketplace.dispute;

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
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.notification.NotificationType;
import com.toni.marketplace.order.IdempotencyKeyRepository;
import com.toni.marketplace.order.OrderRepository;
import com.toni.marketplace.order.OrderStatus;
import com.toni.marketplace.order.PaymentService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Order disputes (backlog #169): the MockMvc window/state/authz matrix,
 * the frozen transitions while DISPUTED, both ADMIN resolutions
 * (refund — incl. the single PSP invocation and the listing case that
 * applied — and release back to the recorded pre-dispute status), and
 * the audit/notification side effects.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@code PriceOfferTest}: rows must be committed by the requests
 * themselves to be asserted here. The window test ages an order by
 * moving its {@code updated_at} directly (the dispute window is
 * measured from it — see {@code DisputeProperties}) instead of moving
 * a shared test clock, so test order cannot matter.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderDisputeTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OrderDisputeRepository disputes;

  @Autowired
  private OrderRepository orders;

  @Autowired
  private IdempotencyKeyRepository keys;

  @Autowired
  private NotificationRepository notifications;

  @Autowired
  private AuditLogRepository auditLog;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository items;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @Autowired
  private PaymentService payments;

  @Autowired
  private JdbcTemplate jdbc;

  @AfterEach
  void cleanUp() {
    disputes.deleteAll();
    notifications.deleteAll();
    auditLog.deleteAll();
    keys.deleteAll();
    orders.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User newUser() {
    String tag = "dispute-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private User newAdmin() {
    String tag = "dispute-admin-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    User admin = new User(tag, tag + "@example.com", passwords.encode("password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    return users.save(admin);
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private Item newItem(User seller, long priceCents) {
    Item item = new Item("Film camera", "Canon AE-1", priceCents, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    return items.save(item);
  }

  /** Creates an order through the real API and pays it: a PAID order on a RESERVED listing. */
  private long paidOrder(User buyer, User seller, Item item) throws Exception {
    String body = mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", "dispute-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + "}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    long orderId = ((Number) JsonPath.read(body, "$.data.id")).longValue();
    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PAID"));
    return orderId;
  }

  private long openDispute(User buyer, long orderId, String reason) throws Exception {
    String body = mockMvc.perform(post("/api/orders/{id}/dispute", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"" + reason + "\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return ((Number) JsonPath.read(body, "$.data.id")).longValue();
  }

  private java.util.List<NotificationType> notificationTypesFor(Long recipientId) {
    return notifications.findByRecipientId(recipientId, PageRequest.of(0, 50))
        .map(n -> n.getType()).getContent();
  }

  private java.util.List<AuditAction> auditActionsFor(long orderId) {
    return auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(AuditTargetType.ORDER, orderId)
        .stream().map(a -> a.getAction()).toList();
  }

  @Test
  void openOnPaidOrder_freezesOrder_notifiesSeller_audits() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long orderId = paidOrder(buyer, seller, item);

    mockMvc.perform(post("/api/orders/{id}/dispute", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"the parcel never arrived\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.orderId").value(orderId))
        .andExpect(jsonPath("$.data.buyerId").value(buyer.getId()))
        .andExpect(jsonPath("$.data.sellerId").value(seller.getId()))
        .andExpect(jsonPath("$.data.status").value("OPEN"))
        .andExpect(jsonPath("$.data.previousStatus").value("PAID"))
        .andExpect(jsonPath("$.data.listingRestored").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.data.resolvedAt").value(org.hamcrest.Matchers.nullValue()));

    assertThat(orders.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.DISPUTED);
    // Opening moves no money and no listing state.
    assertThat(items.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.RESERVED);
    // The seller's inbox also holds the ORDER_PAID notification from
    // the pay step (#157) — the dispute notification appends after it.
    assertThat(notificationTypesFor(seller.getId()))
        .containsExactly(NotificationType.ORDER_PAID,
            NotificationType.ORDER_DISPUTED);
    assertThat(auditActionsFor(orderId))
        .contains(AuditAction.ORDER_PAID, AuditAction.ORDER_DISPUTED);
  }

  @Test
  void openOnCompletedOrder_recordsPreviousCompleted() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long orderId = paidOrder(buyer, seller, item);
    mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/orders/{id}/dispute", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"the camera shutter is broken\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.previousStatus").value("COMPLETED"));
    assertThat(orders.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.DISPUTED);
  }

  @Test
  void openByNonBuyer_forbidden_anonymous401_unknown404() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User stranger = newUser();
    Item item = newItem(seller, 10_000L);
    long orderId = paidOrder(buyer, seller, item);

    mockMvc.perform(post("/api/orders/{id}/dispute", orderId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"seller cannot dispute\"}"))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/api/orders/{id}/dispute", orderId)
            .header("Authorization", "Bearer " + token(stranger))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"stranger cannot dispute\"}"))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/api/orders/{id}/dispute", orderId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"anonymous\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/orders/{id}/dispute", 999_999L)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"no such order\"}"))
        .andExpect(status().isNotFound());
    assertThat(disputes.findByOrderId(orderId)).isEmpty();
  }

  @Test
  void openOnPendingOrder_422() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    String body = mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", "dispute-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + "}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    long orderId = ((Number) JsonPath.read(body, "$.data.id")).longValue();

    mockMvc.perform(post("/api/orders/{id}/dispute", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"not paid yet\"}"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void openWithBlankOrOverlongReason_400() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long orderId = paidOrder(buyer, seller, item);

    mockMvc.perform(post("/api/orders/{id}/dispute", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"  \"}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/api/orders/{id}/dispute", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"" + "x".repeat(501) + "\"}"))
        .andExpect(status().isBadRequest());
    assertThat(disputes.findByOrderId(orderId)).isEmpty();
  }

  @Test
  void secondOpenWhileOpen_returnsSameDispute_noDuplicateSideEffects() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long orderId = paidOrder(buyer, seller, item);

    long first = openDispute(buyer, orderId, "never arrived");
    long second = openDispute(buyer, orderId, "never arrived (retry)");

    assertThat(second).isEqualTo(first);
    assertThat(disputes.findByOrderId(orderId)).hasSize(1);
    // The seller's inbox also holds the ORDER_PAID notification from
    // the pay step (#157) — the dispute notification appends after it.
    assertThat(notificationTypesFor(seller.getId()))
        .containsExactly(NotificationType.ORDER_PAID,
            NotificationType.ORDER_DISPUTED);
    assertThat(auditActionsFor(orderId))
        .filteredOn(a -> a == AuditAction.ORDER_DISPUTED).hasSize(1);
  }

  @Test
  void openAfterWindow_422() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long orderId = paidOrder(buyer, seller, item);
    // Age the order past the 7-day window: the window is measured from
    // updated_at (the PAID transition), so move that timestamp directly.
    jdbc.update("UPDATE orders SET updated_at = ? WHERE id = ?",
        Timestamp.from(Instant.now().minus(Duration.ofDays(8))), orderId);

    mockMvc.perform(post("/api/orders/{id}/dispute", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"too late\"}"))
        .andExpect(status().isUnprocessableEntity());
    assertThat(disputes.findByOrderId(orderId)).isEmpty();
  }

  @Test
  void transitionsWhileDisputed_allFrozen422() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long orderId = paidOrder(buyer, seller, item);
    openDispute(buyer, orderId, "never arrived");

    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isUnprocessableEntity());
    mockMvc.perform(post("/api/orders/{id}/cancel", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isUnprocessableEntity());
    mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isUnprocessableEntity());
    mockMvc.perform(post("/api/orders/{id}/refund", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isUnprocessableEntity());
    assertThat(orders.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.DISPUTED);
  }

  @Test
  void resolveRefundOnPaidDispute_refundsOnce_restoresListing_notifiesBoth() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User admin = newAdmin();
    Item item = newItem(seller, 10_000L);
    long orderId = paidOrder(buyer, seller, item);
    long disputeId = openDispute(buyer, orderId, "never arrived");
    long refundsBefore = payments.refundsIssued();

    mockMvc.perform(post("/api/admin/disputes/{id}/resolve-refund", disputeId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("RESOLVED_REFUND"))
        .andExpect(jsonPath("$.data.listingRestored").value(true))
        .andExpect(jsonPath("$.data.resolvedAt").exists());

    assertThat(orders.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.REFUNDED);
    assertThat(orders.findById(orderId).orElseThrow().getRefundId()).isNotNull();
    assertThat(items.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.AVAILABLE);
    assertThat(payments.refundsIssued() - refundsBefore).isEqualTo(1);
    assertThat(notificationTypesFor(buyer.getId()))
        .containsExactly(NotificationType.DISPUTE_RESOLVED_REFUND);
    assertThat(notificationTypesFor(seller.getId()))
        .containsExactly(NotificationType.ORDER_PAID,
            NotificationType.ORDER_DISPUTED,
            NotificationType.DISPUTE_RESOLVED_REFUND);
    assertThat(auditActionsFor(orderId))
        .contains(AuditAction.ORDER_DISPUTE_RESOLVED_REFUND);

    // Idempotent re-resolve: no second PSP refund, no second audit row.
    mockMvc.perform(post("/api/admin/disputes/{id}/resolve-refund", disputeId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("RESOLVED_REFUND"));
    assertThat(payments.refundsIssued() - refundsBefore).isEqualTo(1);
    assertThat(auditActionsFor(orderId))
        .filteredOn(a -> a == AuditAction.ORDER_DISPUTE_RESOLVED_REFUND).hasSize(1);

    // The opposite resolution on a resolved dispute is 422.
    mockMvc.perform(post("/api/admin/disputes/{id}/resolve-release", disputeId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void resolveRefundOnCompletedDispute_listingStaysSold() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User admin = newAdmin();
    Item item = newItem(seller, 10_000L);
    long orderId = paidOrder(buyer, seller, item);
    mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk());
    long disputeId = openDispute(buyer, orderId, "shutter broken");
    long refundsBefore = payments.refundsIssued();

    mockMvc.perform(post("/api/admin/disputes/{id}/resolve-refund", disputeId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.listingRestored").value(false));

    assertThat(orders.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.REFUNDED);
    assertThat(items.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.SOLD);
    assertThat(payments.refundsIssued() - refundsBefore).isEqualTo(1);
  }

  @Test
  void resolveRelease_restoresPaid_lifecycleResumes_redispute422() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User admin = newAdmin();
    Item item = newItem(seller, 10_000L);
    long orderId = paidOrder(buyer, seller, item);
    long disputeId = openDispute(buyer, orderId, "slow post, actually fine");

    mockMvc.perform(post("/api/admin/disputes/{id}/resolve-release", disputeId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("RESOLVED_RELEASE"))
        .andExpect(jsonPath("$.data.listingRestored").value(org.hamcrest.Matchers.nullValue()));

    assertThat(orders.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.PAID);
    assertThat(items.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.RESERVED);
    assertThat(notificationTypesFor(buyer.getId()))
        .containsExactly(NotificationType.DISPUTE_RESOLVED_RELEASE);
    assertThat(auditActionsFor(orderId))
        .contains(AuditAction.ORDER_DISPUTE_RESOLVED_RELEASE);

    // The lifecycle resumes: the seller can complete the released order.
    mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("COMPLETED"));

    // A resolved dispute is final: re-disputing the order is 422.
    mockMvc.perform(post("/api/orders/{id}/dispute", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"second bite\"}"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void adminQueue_requiresAdmin_filtersByStatus_unknownDispute404() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User admin = newAdmin();
    Item item = newItem(seller, 10_000L);
    long orderId = paidOrder(buyer, seller, item);
    openDispute(buyer, orderId, "never arrived");

    mockMvc.perform(get("/api/admin/disputes")
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].orderId").value(orderId));
    mockMvc.perform(get("/api/admin/disputes").param("status", "RESOLVED_REFUND")
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(0));
    mockMvc.perform(get("/api/admin/disputes")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/api/admin/disputes"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/admin/disputes/{id}/resolve-refund", 999_999L)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/api/admin/disputes/{id}/resolve-refund", 999_999L)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isNotFound());
  }
}
