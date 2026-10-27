package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.toni.marketplace.notification.Notification;
import com.toni.marketplace.notification.NotificationPreferenceRepository;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.notification.NotificationType;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Buyer notification on ship (backlog #238): shipping a PAID order
 * records exactly one ORDER_SHIPPED row for the buyer in the same
 * transaction as the status flip — never for the shipper, never on a
 * rejected re-ship, and never when the buyer muted the type (#216).
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link OrderShipmentTest}: each test owns its data via unique users
 * and wipes the touched tables in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderShipNotificationTest {

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
  private NotificationRepository notifications;

  @Autowired
  private NotificationPreferenceRepository preferences;

  @AfterEach
  void cleanUp() {
    preferences.deleteAll();
    notifications.deleteAll();
    keys.deleteAll();
    orders.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User newUser() {
    String tag = "shipnotif-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
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
            .header("Idempotency-Key", "shipnotif-" + UUID.randomUUID())
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

  private long paidOrder(User buyer, Item item) throws Exception {
    long orderId = createOrder(buyer, item);
    payOrder(buyer, orderId);
    return orderId;
  }

  private void shipOrder(User shipper, long orderId) throws Exception {
    mockMvc.perform(post("/api/orders/{id}/ship", orderId)
            .header("Authorization", "Bearer " + token(shipper))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"carrier\":\"SF Express\",\"trackingNumber\":\"SF123456789\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("SHIPPED"));
  }

  private List<Notification> rowsFor(User user) {
    return notifications.findAll().stream()
        .filter(n -> user.getId().equals(n.getRecipientId()))
        .collect(Collectors.toList());
  }

  @Test
  void shipNotifiesBuyerOnce_andNotTheSeller() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);
    long orderId = paidOrder(buyer, item);

    // Pay notified the seller only — the buyer starts with no rows.
    assertThat(rowsFor(buyer)).isEmpty();
    assertThat(rowsFor(seller))
        .extracting(Notification::getType)
        .containsExactly(NotificationType.ORDER_PAID);

    shipOrder(seller, orderId);

    assertThat(rowsFor(buyer))
        .extracting(Notification::getType)
        .containsExactly(NotificationType.ORDER_SHIPPED);
    assertThat(rowsFor(buyer).get(0).getOrderId()).isEqualTo(orderId);
    // The shipper gained no row from their own ship.
    assertThat(rowsFor(seller))
        .extracting(Notification::getType)
        .containsExactly(NotificationType.ORDER_PAID);
  }

  @Test
  void adminShipNotifiesBuyer_notTheAdmin() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User admin = newAdmin();
    Item item = newItem(seller);
    long orderId = paidOrder(buyer, item);

    shipOrder(admin, orderId);

    assertThat(rowsFor(buyer))
        .extracting(Notification::getType)
        .containsExactly(NotificationType.ORDER_SHIPPED);
    assertThat(rowsFor(admin)).isEmpty();
  }

  @Test
  void rejectedReshipNotifiesNobody() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);
    long orderId = paidOrder(buyer, item);
    shipOrder(seller, orderId);
    assertThat(rowsFor(buyer)).hasSize(1);

    mockMvc.perform(post("/api/orders/{id}/ship", orderId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"carrier\":\"YTO\",\"trackingNumber\":\"YT0001\"}"))
        .andExpect(status().isUnprocessableEntity());

    // Still exactly the one row from the first, accepted ship.
    assertThat(rowsFor(buyer))
        .extracting(Notification::getType)
        .containsExactly(NotificationType.ORDER_SHIPPED);
  }

  @Test
  void mutedPreferenceSuppressesShipNotification() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);
    long orderId = paidOrder(buyer, item);

    mockMvc.perform(put("/api/notifications/preferences/ORDER_SHIPPED")
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"enabled\":false}"))
        .andExpect(status().isOk());

    shipOrder(seller, orderId);

    assertThat(rowsFor(buyer)).isEmpty();
  }

  @Test
  void shippedRowAppearsInBuyersNotificationList() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);
    long orderId = paidOrder(buyer, item);
    shipOrder(seller, orderId);

    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/notifications")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].type").value("ORDER_SHIPPED"))
        .andExpect(jsonPath("$.data.content[0].orderId").value(orderId));
  }
}
