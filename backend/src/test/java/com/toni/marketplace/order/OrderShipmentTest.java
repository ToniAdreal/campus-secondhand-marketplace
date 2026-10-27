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
import com.toni.marketplace.dispute.OrderDisputeRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.notification.NotificationPreferenceRepository;
import com.toni.marketplace.notification.NotificationRepository;
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
 * Shipped fulfilment path (backlog #222): POST /api/orders/{id}/ship
 * (PAID → SHIPPED, seller/ADMIN only, carrier + tracking number
 * recorded, listing stays RESERVED) and POST
 * /api/orders/{id}/confirm-receipt (SHIPPED → COMPLETED, buyer only,
 * same RESERVED → SOLD flip and #221 settlement row as the handover
 * completion), plus the cross-path 422s that keep the two completion
 * paths mutually exclusive, and the SHIPPED dispute eligibility.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link OrderCompleteTest}: each test owns its data via unique users
 * and wipes the touched tables in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderShipmentTest {

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
  private OrderDisputeRepository disputes;

  @Autowired
  private AuditLogRepository auditLog;

  @Autowired
  private SellerPayoutRepository payouts;

  @Autowired
  private NotificationRepository notifications;

  @Autowired
  private NotificationPreferenceRepository preferences;

  @AfterEach
  void cleanUp() {
    preferences.deleteAll();
    notifications.deleteAll();
    disputes.deleteAll();
    auditLog.deleteAll();
    payouts.deleteAll();
    keys.deleteAll();
    orders.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User newUser() {
    String tag = "ship-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private User newAdmin() {
    User admin = newUser();
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
            .header("Idempotency-Key", "ship-" + UUID.randomUUID())
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
        .andExpect(jsonPath("$.data.status").value("PAID"));
  }

  private long paidOrder(User buyer, User seller, Item item) throws Exception {
    long orderId = createOrder(buyer, item);
    payOrder(buyer, orderId);
    return orderId;
  }

  private void shipOrder(User seller, long orderId) throws Exception {
    mockMvc.perform(post("/api/orders/{id}/ship", orderId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"carrier\":\"SF Express\",\"trackingNumber\":\"SF123456789\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("SHIPPED"));
  }

  @Test
  void shipHappyPath_recordsCarrierAndTracking_listingStaysReserved_audits() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);
    long orderId = paidOrder(buyer, seller, item);

    String body = mockMvc.perform(post("/api/orders/{id}/ship", orderId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"carrier\":\"SF Express\",\"trackingNumber\":\"SF123456789\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("SHIPPED"))
        .andExpect(jsonPath("$.data.carrier").value("SF Express"))
        .andExpect(jsonPath("$.data.trackingNumber").value("SF123456789"))
        .andExpect(jsonPath("$.data.shippedAt").exists())
        .andReturn().getResponse().getContentAsString();
    assertThat(JsonPath.<String>read(body, "$.data.shippedAt")).isNotBlank();
    assertThat(items.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.RESERVED);
    assertThat(auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(AuditTargetType.ORDER, orderId))
        .extracting(a -> a.getAction())
        .contains(AuditAction.ORDER_SHIPPED);
  }

  @Test
  void shipAuthz_buyerStrangerAnonymousUnknown() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User stranger = newUser();
    Item item = newItem(seller);
    long orderId = paidOrder(buyer, seller, item);

    mockMvc.perform(post("/api/orders/{id}/ship", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"carrier\":\"SF Express\",\"trackingNumber\":\"SF123456789\"}"))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/api/orders/{id}/ship", orderId)
            .header("Authorization", "Bearer " + token(stranger))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"carrier\":\"SF Express\",\"trackingNumber\":\"SF123456789\"}"))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/api/orders/{id}/ship", orderId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"carrier\":\"SF Express\",\"trackingNumber\":\"SF123456789\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/orders/{id}/ship", 999999L)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"carrier\":\"SF Express\",\"trackingNumber\":\"SF123456789\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void shipState_pendingReshipAndHandoverCompleted_all422() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);
    long pendingId = createOrder(buyer, item);
    mockMvc.perform(post("/api/orders/{id}/ship", pendingId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"carrier\":\"SF Express\",\"trackingNumber\":\"SF123456789\"}"))
        .andExpect(status().isUnprocessableEntity());

    // The PENDING order above still holds the listing RESERVED, so the
    // second scenario needs its own listing.
    Item item2 = newItem(seller);
    long orderId = paidOrder(buyer, seller, item2);
    shipOrder(seller, orderId);
    mockMvc.perform(post("/api/orders/{id}/ship", orderId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"carrier\":\"YTO\",\"trackingNumber\":\"YT0001\"}"))
        .andExpect(status().isUnprocessableEntity());
    // Tracking from the first ship survives the rejected re-ship.
    mockMvc.perform(get("/api/orders/{id}", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.trackingNumber").value("SF123456789"));

    // Handover-completed order: ship is 422 (cross-path exclusivity).
    Item item3 = newItem(seller);
    long handoverId = paidOrder(buyer, seller, item3);
    String codeBody = mockMvc.perform(post("/api/orders/{id}/handover-code/regenerate", handoverId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    String code = JsonPath.read(codeBody, "$.data.handoverCode");
    mockMvc.perform(post("/api/orders/{id}/complete", handoverId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"handoverCode\":\"" + code + "\"}"))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/orders/{id}/ship", handoverId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"carrier\":\"SF Express\",\"trackingNumber\":\"SF999\"}"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void shipValidation_blankCarrierAndOverlongTracking_400() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);
    long orderId = paidOrder(buyer, seller, item);

    mockMvc.perform(post("/api/orders/{id}/ship", orderId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"carrier\":\"  \",\"trackingNumber\":\"SF123456789\"}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/api/orders/{id}/ship", orderId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"carrier\":\"SF Express\",\"trackingNumber\":\"" + "x".repeat(129) + "\"}"))
        .andExpect(status().isBadRequest());
    // Both rejections left the order PAID — a valid ship still works.
    shipOrder(seller, orderId);
  }

  @Test
  void confirmReceiptHappyPath_completesAndSellsAndSettles() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);
    long orderId = paidOrder(buyer, seller, item);
    shipOrder(seller, orderId);

    mockMvc.perform(post("/api/orders/{id}/confirm-receipt", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("COMPLETED"))
        .andExpect(jsonPath("$.data.carrier").value("SF Express"))
        .andExpect(jsonPath("$.data.trackingNumber").value("SF123456789"));
    // completedAt lives on the receipt projection, not on OrderDto (#180):
    // the receipt must now carry the real completion instant.
    mockMvc.perform(get("/api/orders/{id}/receipt", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.completedAt").exists());
    assertThat(items.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.SOLD);
    assertThat(payouts.findByOrderId(orderId)).isPresent();
    assertThat(auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(AuditTargetType.ORDER, orderId))
        .extracting(a -> a.getAction())
        .contains(AuditAction.ORDER_SHIPPED, AuditAction.ORDER_RECEIPT_CONFIRMED);
  }

  @Test
  void confirmReceiptAuthzAndState_sellerStrangerAnonymousPaidAndIdempotent() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User stranger = newUser();
    Item item = newItem(seller);
    long orderId = paidOrder(buyer, seller, item);

    // PAID (not yet shipped) → 422.
    mockMvc.perform(post("/api/orders/{id}/confirm-receipt", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isUnprocessableEntity());

    shipOrder(seller, orderId);
    mockMvc.perform(post("/api/orders/{id}/confirm-receipt", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/api/orders/{id}/confirm-receipt", orderId)
            .header("Authorization", "Bearer " + token(stranger)))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/api/orders/{id}/confirm-receipt", orderId))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/orders/{id}/confirm-receipt", 999999L)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isNotFound());

    // Handover complete on a SHIPPED order → 422 (cross-path exclusivity).
    mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"handoverCode\":\"000000\"}"))
        .andExpect(status().isUnprocessableEntity());

    // Buyer confirms, then re-confirms idempotently.
    mockMvc.perform(post("/api/orders/{id}/confirm-receipt", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    mockMvc.perform(post("/api/orders/{id}/confirm-receipt", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("COMPLETED"));
  }

  @Test
  void shippedOrderCanBeDisputedAndReleasedBackToShipped() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User admin = newAdmin();
    Item item = newItem(seller);
    long orderId = paidOrder(buyer, seller, item);
    shipOrder(seller, orderId);

    String disputeBody = mockMvc.perform(post("/api/orders/{id}/dispute", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Parcel never arrived\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    long disputeId = ((Number) JsonPath.read(disputeBody, "$.data.id")).longValue();

    mockMvc.perform(get("/api/orders/{id}", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("DISPUTED"));

    mockMvc.perform(post("/api/admin/disputes/{id}/resolve-release", disputeId)
            .header("Authorization", "Bearer " + token(admin))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"resolutionNote\":\"Shipment test release.\"}"))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/orders/{id}", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("SHIPPED"))
        .andExpect(jsonPath("$.data.trackingNumber").value("SF123456789"));
  }
}
