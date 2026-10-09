package com.toni.marketplace.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.util.List;
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
 * Backlog #98 — audit rows for the order transitions, end-to-end via
 * MockMvc: complete and refund each append exactly one row with the seller
 * as actor and the order as target; idempotent re-complete / re-refund
 * append none.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@code OrderCompleteTest}: each test owns its data via unique users and
 * wipes the touched tables in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditLogOrderTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AuditLogRepository auditLog;

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

  @AfterEach
  void cleanUp() {
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

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  /** Creates + pays an order; returns its id. */
  private long paidOrder(User seller, User buyer) throws Exception {
    Item item = new Item("Film camera", "Canon AE-1", 88000L, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    item = items.save(item);

    String body = mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", "key-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + "}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    long orderId = ((Number) JsonPath.read(body, "$.data.id")).longValue();

    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk());
    return orderId;
  }

  private List<AuditLog> rowsFor(long orderId) {
    return auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(AuditTargetType.ORDER, orderId);
  }

  @Test
  void completeWritesExactlyOneRow_andRecompleteWritesNone() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    long orderId = paidOrder(seller, buyer);

    mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk());
    // Idempotent replay: returns the order, appends no second row.
    mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk());

    List<AuditLog> rows = rowsFor(orderId);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getAction()).isEqualTo(AuditAction.ORDER_COMPLETED);
    assertThat(rows.get(0).getActorUserId()).isEqualTo(seller.getId());
    assertThat(rows.get(0).getCreatedAt()).isNotNull();
  }

  @Test
  void refundWritesExactlyOneRow_andRerefundWritesNone() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    long orderId = paidOrder(seller, buyer);

    mockMvc.perform(post("/api/orders/{id}/refund", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/orders/{id}/refund", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk());

    List<AuditLog> rows = rowsFor(orderId);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getAction()).isEqualTo(AuditAction.ORDER_REFUNDED);
    assertThat(rows.get(0).getActorUserId()).isEqualTo(seller.getId());
  }

  @Test
  void forbiddenCompleteWritesNoRow() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    long orderId = paidOrder(seller, buyer);

    // The buyer may not complete their own order (seller-only transition).
    mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isForbidden());

    assertThat(rowsFor(orderId)).isEmpty();
  }
}
