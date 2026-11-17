package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditLog;
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
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADMIN payout mark-paid audit rows (backlog #390): POST
 * /api/admin/payouts/{id}/mark-paid moves a PAYABLE settlement row to
 * PAID and writes exactly one {@code PAYOUT_MARKED_PAID} row to the
 * append-only trail from inside the transition's own transaction, with
 * the acting ADMIN as actor and the payout row as target. A re-mark of
 * an already-PAID row is idempotent and writes no second row. The
 * hourly sweep's own PAYABLE → PAID flip writes no row — it is a
 * system transition with no actor (the #122 rule) — so this endpoint is
 * the audited mark-paid. The ADMIN audit-log read picks the rows up
 * through its existing filters.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring {@link
 * com.toni.marketplace.offer.OfferAuditTest}: the rows must be committed
 * by the requests themselves to be asserted here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminPayoutMarkPaidAuditTest {

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
  private SellerPayoutRepository payouts;

  @Autowired
  private AuditLogRepository auditLog;

  @Autowired
  private NotificationRepository notifications;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @AfterEach
  void cleanUp() {
    notifications.deleteAll();
    auditLog.deleteAll();
    payouts.deleteAll();
    orders.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User newUser(String role) {
    String tag = "payout-audit-" + role + "-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private User newAdmin() {
    String tag = "payout-audit-admin-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    User admin = new User(tag, tag + "@example.com", passwords.encode("password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    return users.save(admin);
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  /** A COMPLETED order with a payout row in the given status. */
  private SellerPayout seedPayout(User seller, User buyer, PayoutStatus status) {
    Item item = new Item("Payout thing " + UUID.randomUUID().toString().substring(0, 8),
        "seeded", 5000L, seller.getId());
    item.setStatus(ItemStatus.SOLD);
    items.save(item);
    Order order = new Order(item, buyer.getId(), 5000L);
    order.setStatus(OrderStatus.COMPLETED);
    orders.save(order);
    SellerPayout payout = new SellerPayout(order.getId(), seller.getId(), 5000L);
    payout.setStatus(status);
    return payouts.save(payout);
  }

  private List<AuditLog> rowsFor(long payoutId) {
    return auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(AuditTargetType.PAYOUT, payoutId);
  }

  @Test
  void markPaid_writesOneRow_actorIsAdmin() throws Exception {
    User admin = newAdmin();
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    SellerPayout payout = seedPayout(seller, buyer, PayoutStatus.PAYABLE);

    mockMvc.perform(post("/api/admin/payouts/{id}/mark-paid", payout.getId())
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(payout.getId()))
        .andExpect(jsonPath("$.data.status").value("PAID"))
        .andExpect(jsonPath("$.data.paidAt").isNotEmpty());

    assertThat(payouts.findById(payout.getId())).isPresent()
        .get().extracting(SellerPayout::getStatus).isEqualTo(PayoutStatus.PAID);
    assertThat(rowsFor(payout.getId())).singleElement().satisfies(row -> {
      assertThat(row.getAction()).isEqualTo(AuditAction.PAYOUT_MARKED_PAID);
      assertThat(row.getActorUserId()).isEqualTo(admin.getId());
      assertThat(row.getTargetType()).isEqualTo(AuditTargetType.PAYOUT);
      assertThat(row.getTargetId()).isEqualTo(payout.getId());
    });
  }

  @Test
  void reMarkPaid_isIdempotentAndWritesNoSecondRow() throws Exception {
    User admin = newAdmin();
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    SellerPayout payout = seedPayout(seller, buyer, PayoutStatus.PAYABLE);

    mockMvc.perform(post("/api/admin/payouts/{id}/mark-paid", payout.getId())
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/admin/payouts/{id}/mark-paid", payout.getId())
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PAID"));

    assertThat(rowsFor(payout.getId())).singleElement().satisfies(row ->
        assertThat(row.getAction()).isEqualTo(AuditAction.PAYOUT_MARKED_PAID));
  }

  @Test
  void pendingPayout_returns422AndWritesNoRow() throws Exception {
    User admin = newAdmin();
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    SellerPayout payout = seedPayout(seller, buyer, PayoutStatus.PENDING);

    mockMvc.perform(post("/api/admin/payouts/{id}/mark-paid", payout.getId())
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isUnprocessableEntity());

    assertThat(payouts.findById(payout.getId())).isPresent()
        .get().extracting(SellerPayout::getStatus).isEqualTo(PayoutStatus.PENDING);
    assertThat(rowsFor(payout.getId())).isEmpty();
  }

  @Test
  void unknownPayout_returns404() throws Exception {
    User admin = newAdmin();

    mockMvc.perform(post("/api/admin/payouts/{id}/mark-paid", 9_999_999L)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isNotFound());
  }

  @Test
  void nonAdmin_returns403() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    SellerPayout payout = seedPayout(seller, buyer, PayoutStatus.PAYABLE);

    mockMvc.perform(post("/api/admin/payouts/{id}/mark-paid", payout.getId())
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isForbidden());

    assertThat(rowsFor(payout.getId())).isEmpty();
  }

  @Test
  void anonymous_returns401() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    SellerPayout payout = seedPayout(seller, buyer, PayoutStatus.PAYABLE);

    mockMvc.perform(post("/api/admin/payouts/{id}/mark-paid", payout.getId()))
        .andExpect(status().isUnauthorized());

    assertThat(rowsFor(payout.getId())).isEmpty();
  }

  @Test
  void adminAuditLogRead_returnsPayoutRowsViaExistingFilters() throws Exception {
    User admin = newAdmin();
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    SellerPayout payout = seedPayout(seller, buyer, PayoutStatus.PAYABLE);

    mockMvc.perform(post("/api/admin/payouts/{id}/mark-paid", payout.getId())
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/admin/audit-log")
            .header("Authorization", "Bearer " + token(admin))
            .param("action", "PAYOUT_MARKED_PAID")
            .param("targetType", "PAYOUT")
            .param("targetId", String.valueOf(payout.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].action").value("PAYOUT_MARKED_PAID"))
        .andExpect(jsonPath("$.data.content[0].actorUserId").value(admin.getId()))
        .andExpect(jsonPath("$.data.content[0].targetType").value("PAYOUT"))
        .andExpect(jsonPath("$.data.content[0].targetId").value(payout.getId()));
  }
}
