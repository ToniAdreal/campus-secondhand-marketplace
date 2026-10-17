package com.toni.marketplace.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.toni.marketplace.order.IdempotencyKeyRepository;
import com.toni.marketplace.order.OrderRepository;
import com.toni.marketplace.order.OrderService;
import java.time.Instant;
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
 * In-app notifications (backlog #157): each order transition writes
 * exactly one notification row for exactly one party, inside the
 * transition's own transaction; the read side is scoped to the caller.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link com.toni.marketplace.order.OrderPaymentTest}: the rows must be
 * committed by the transition requests themselves to be asserted here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private NotificationRepository notifications;

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
  private OrderService orderService;

  @AfterEach
  void cleanUp() {
    notifications.deleteAll();
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

  private void transition(User caller, long orderId, String action) throws Exception {
    mockMvc.perform(post("/api/orders/{id}/" + action, orderId)
            .header("Authorization", "Bearer " + token(caller)))
        .andExpect(status().isOk());
  }

  private void expectUnreadCount(User user, long expected) throws Exception {
    mockMvc.perform(get("/api/notifications/unread-count")
            .header("Authorization", "Bearer " + token(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data").value(expected));
  }

  @Test
  void payNotifiesTheSellerOnly_andRepayWritesNoDuplicate() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    long orderId = createOrder(buyer, newItem(seller));

    transition(buyer, orderId, "pay");

    assertThat(notifications.findAll()).singleElement().satisfies(n -> {
      assertThat(n.getRecipientId()).isEqualTo(seller.getId());
      assertThat(n.getType()).isEqualTo(NotificationType.ORDER_PAID);
      assertThat(n.getOrderId()).isEqualTo(orderId);
      assertThat(n.getReadAt()).isNull();
    });
    expectUnreadCount(seller, 1);
    expectUnreadCount(buyer, 0);

    // The seller's list shows the row; the buyer's list is empty.
    mockMvc.perform(get("/api/notifications")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].type").value("ORDER_PAID"))
        .andExpect(jsonPath("$.data.content[0].orderId").value(orderId));
    mockMvc.perform(get("/api/notifications")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(0));

    // Idempotent re-pay: the transition guard returns before the write
    // point, so no duplicate row appears.
    transition(buyer, orderId, "pay");
    assertThat(notifications.findAll()).hasSize(1);
    expectUnreadCount(seller, 1);
  }

  @Test
  void cancelNotifiesTheSellerOnly() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    long orderId = createOrder(buyer, newItem(seller));

    transition(buyer, orderId, "cancel");

    assertThat(notifications.findAll()).singleElement().satisfies(n -> {
      assertThat(n.getRecipientId()).isEqualTo(seller.getId());
      assertThat(n.getType()).isEqualTo(NotificationType.ORDER_CANCELLED);
      assertThat(n.getOrderId()).isEqualTo(orderId);
    });
    expectUnreadCount(seller, 1);
    expectUnreadCount(buyer, 0);
  }

  @Test
  void completeNotifiesTheBuyerOnly() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    long orderId = createOrder(buyer, newItem(seller));
    transition(buyer, orderId, "pay");
    notifications.deleteAll();

    // #179: the seller completes with the buyer's handover code,
    // fetched through the buyer-only regenerate surface.
    String codeBody = mockMvc.perform(post("/api/orders/{id}/handover-code/regenerate", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    String code = JsonPath.read(codeBody, "$.data.handoverCode");
    mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"handoverCode\":\"" + code + "\"}"))
        .andExpect(status().isOk());

    assertThat(notifications.findAll()).singleElement().satisfies(n -> {
      assertThat(n.getRecipientId()).isEqualTo(buyer.getId());
      assertThat(n.getType()).isEqualTo(NotificationType.ORDER_COMPLETED);
      assertThat(n.getOrderId()).isEqualTo(orderId);
    });
    expectUnreadCount(buyer, 1);
    expectUnreadCount(seller, 0);
  }

  @Test
  void refundNotifiesTheBuyerOnly() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    long orderId = createOrder(buyer, newItem(seller));
    transition(buyer, orderId, "pay");
    notifications.deleteAll();

    transition(seller, orderId, "refund");

    assertThat(notifications.findAll()).singleElement().satisfies(n -> {
      assertThat(n.getRecipientId()).isEqualTo(buyer.getId());
      assertThat(n.getType()).isEqualTo(NotificationType.ORDER_REFUNDED);
      assertThat(n.getOrderId()).isEqualTo(orderId);
    });
    expectUnreadCount(buyer, 1);
    expectUnreadCount(seller, 0);
  }

  @Test
  void expiryNotifiesTheBuyerOnly() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    long orderId = createOrder(buyer, newItem(seller));

    boolean expired = orderService.expireStaleOrder(orderId, Instant.now().plusSeconds(3600));

    assertThat(expired).isTrue();
    assertThat(notifications.findAll()).singleElement().satisfies(n -> {
      assertThat(n.getRecipientId()).isEqualTo(buyer.getId());
      assertThat(n.getType()).isEqualTo(NotificationType.ORDER_EXPIRED);
      assertThat(n.getOrderId()).isEqualTo(orderId);
    });
    expectUnreadCount(buyer, 1);
    expectUnreadCount(seller, 0);
  }

  @Test
  void markReadFlow_andAnotherUsersRowIs404_andAnonymousIs401() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    long orderId = createOrder(buyer, newItem(seller));
    transition(buyer, orderId, "pay");
    Long notificationId = notifications.findAll().get(0).getId();

    // Anonymous callers get the 401 envelope on every route.
    mockMvc.perform(get("/api/notifications"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    mockMvc.perform(get("/api/notifications/unread-count"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/notifications/{id}/read", notificationId))
        .andExpect(status().isUnauthorized());

    // The buyer cannot mark the seller's row read — 404, no existence
    // leak — and the row stays unread.
    mockMvc.perform(post("/api/notifications/{id}/read", notificationId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isNotFound());
    expectUnreadCount(seller, 1);

    // The recipient marks it read; the count drops and a re-mark keeps
    // the first stamp (idempotent).
    String body = mockMvc.perform(post("/api/notifications/{id}/read", notificationId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(notificationId))
        .andExpect(jsonPath("$.data.readAt").isNotEmpty())
        .andReturn().getResponse().getContentAsString();
    String firstReadAt = JsonPath.read(body, "$.data.readAt");
    expectUnreadCount(seller, 0);

    // The re-mark returns the same stamp. Compared as parsed instants
    // within one microsecond: the first response serializes the
    // in-memory entity at nanosecond precision, while the re-mark loads
    // the row back from the database, whose TIMESTAMP column stores
    // microsecond precision and rounds the nanosecond value to the
    // nearest microsecond — the stamp is the same moment, its string
    // form can differ by one microsecond.
    String secondBody = mockMvc.perform(post("/api/notifications/{id}/read", notificationId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    String secondReadAt = JsonPath.read(secondBody, "$.data.readAt");
    assertThat(Math.abs(java.time.Duration.between(
            Instant.parse(firstReadAt), Instant.parse(secondReadAt)).toNanos()))
        .isLessThanOrEqualTo(1000);
    expectUnreadCount(seller, 0);
  }

  @Test
  void markAllReadStampsOnlyCallersUnreadRows_andDeleteIsScoped() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    // Seed directly: three unread + one already-read row for the seller,
    // one unread row for the buyer (isolation probe).
    Notification a = notifications.save(
        new Notification(seller.getId(), NotificationType.ORDER_PAID, 101L));
    notifications.save(new Notification(seller.getId(), NotificationType.ORDER_CANCELLED, 102L));
    notifications.save(new Notification(seller.getId(), NotificationType.ORDER_PAID, 103L));
    Notification alreadyRead = notifications.save(
        new Notification(seller.getId(), NotificationType.ORDER_PAID, 104L));
    alreadyRead.markRead(Instant.now().minusSeconds(60));
    notifications.save(alreadyRead);
    Notification buyersRow = notifications.save(
        new Notification(buyer.getId(), NotificationType.ORDER_COMPLETED, 105L));
    expectUnreadCount(seller, 3);
    expectUnreadCount(buyer, 1);

    // Anonymous callers get 401 on both new routes.
    mockMvc.perform(post("/api/notifications/read-all"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(delete("/api/notifications/{id}", a.getId()))
        .andExpect(status().isUnauthorized());

    // Mark-all stamps exactly the caller's 3 unread rows and returns 3;
    // the buyer's row is untouched.
    mockMvc.perform(post("/api/notifications/read-all")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value(3));
    expectUnreadCount(seller, 0);
    expectUnreadCount(buyer, 1);
    assertThat(notifications.findById(a.getId()).orElseThrow().getReadAt()).isNotNull();

    // Idempotent: a second mark-all returns 0, never an error.
    mockMvc.perform(post("/api/notifications/read-all")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value(0));

    // Delete: the buyer cannot delete the seller's row — 404, no
    // existence leak, and the row survives.
    mockMvc.perform(delete("/api/notifications/{id}", a.getId())
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isNotFound());
    assertThat(notifications.findById(a.getId())).isPresent();
    // Unknown id is the same 404.
    mockMvc.perform(delete("/api/notifications/{id}", 999999L)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isNotFound());

    // Deleting an unread row (the buyer's own) drops the unread count.
    mockMvc.perform(delete("/api/notifications/{id}", buyersRow.getId())
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk());
    assertThat(notifications.findById(buyersRow.getId())).isEmpty();
    expectUnreadCount(buyer, 0);

    // Deleting a read row works too; a repeat delete is 404.
    mockMvc.perform(delete("/api/notifications/{id}", a.getId())
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk());
    mockMvc.perform(delete("/api/notifications/{id}", a.getId())
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isNotFound());
  }
}
