package com.toni.marketplace.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mock-PSP settlement summary (backlog #134): GET
 * /api/admin/payments/settlement — ADMIN-only, reconciled from the orders
 * table. Captured sums the amountCents of orders carrying a capture id (a
 * REFUNDED order was still captured, so it counts in both totals), refunded
 * sums REFUNDED orders, net is the difference, and PENDING contributes
 * nothing to any money total. Orders are seeded directly on the rows —
 * including the persisted PSP ids (#114) — so the exact sums pin the
 * aggregate queries, not the pay/refund flows that produce them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminSettlementTest {

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
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  private String adminToken;
  private String userToken;
  private User seller;
  private User buyer;

  @BeforeEach
  void createUsersAndTokens() {
    String tag = "stl-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    User admin = new User(tag + "-admin", tag + "-admin@example.com",
        passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    admin = users.save(admin);
    seller = users.save(new User(tag + "-seller", tag + "-seller@example.com",
        passwords.encode("seller-password")));
    buyer = users.save(new User(tag + "-buyer", tag + "-buyer@example.com",
        passwords.encode("buyer-password")));
    adminToken = jwt.createTokenPair(admin).accessToken();
    userToken = jwt.createTokenPair(buyer).accessToken();
  }

  private Order seedOrder(OrderStatus status, long amountCents, String captureId, String refundId) {
    Item item = new Item("Settlement item " + UUID.randomUUID().toString().substring(0, 8),
        "seeded", amountCents, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    item = items.save(item);
    Order order = new Order(item, buyer.getId(), amountCents);
    order.setStatus(status);
    order.setCaptureId(captureId);
    order.setRefundId(refundId);
    return orders.save(order);
  }

  @Test
  void adminGetsExactSumsOverPaidRefundedPendingMix() throws Exception {
    seedOrder(OrderStatus.PAID, 10_000L, "cap_mock_paid", null);
    seedOrder(OrderStatus.COMPLETED, 20_000L, "cap_mock_completed", null);
    seedOrder(OrderStatus.REFUNDED, 5_000L, "cap_mock_refunded", "rfd_mock_refunded");
    seedOrder(OrderStatus.PENDING, 7_000L, null, null);
    seedOrder(OrderStatus.CANCELLED, 3_000L, null, null);

    mockMvc.perform(get("/api/admin/payments/settlement")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        // Captured: PAID 10000 + COMPLETED 20000 + REFUNDED 5000 (a
        // refunded order was still captured). PENDING/CANCELLED carry no
        // capture id and contribute nothing.
        .andExpect(jsonPath("$.data.capturedCents").value(35_000))
        .andExpect(jsonPath("$.data.refundedCents").value(5_000))
        .andExpect(jsonPath("$.data.netCents").value(30_000))
        .andExpect(jsonPath("$.data.totalOrders").value(5))
        .andExpect(jsonPath("$.data.pendingCount").value(1))
        .andExpect(jsonPath("$.data.paidCount").value(1))
        .andExpect(jsonPath("$.data.completedCount").value(1))
        .andExpect(jsonPath("$.data.cancelledCount").value(1))
        .andExpect(jsonPath("$.data.refundedCount").value(1));
  }

  @Test
  void emptyDatabaseYieldsZerosNotAnError() throws Exception {
    mockMvc.perform(get("/api/admin/payments/settlement")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.capturedCents").value(0))
        .andExpect(jsonPath("$.data.refundedCents").value(0))
        .andExpect(jsonPath("$.data.netCents").value(0))
        .andExpect(jsonPath("$.data.totalOrders").value(0))
        .andExpect(jsonPath("$.data.pendingCount").value(0))
        .andExpect(jsonPath("$.data.paidCount").value(0))
        .andExpect(jsonPath("$.data.completedCount").value(0))
        .andExpect(jsonPath("$.data.cancelledCount").value(0))
        .andExpect(jsonPath("$.data.refundedCount").value(0));
  }

  @Test
  void nonAdminGets403Envelope() throws Exception {
    mockMvc.perform(get("/api/admin/payments/settlement")
            .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
  }

  @Test
  void anonymousGets401Envelope() throws Exception {
    mockMvc.perform(get("/api/admin/payments/settlement"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }
}
