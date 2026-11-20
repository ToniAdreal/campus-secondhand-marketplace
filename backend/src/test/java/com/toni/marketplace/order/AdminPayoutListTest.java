package com.toni.marketplace.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.RefreshTokenRepository;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADMIN payout ledger read (backlog #405): GET /api/admin/payouts lists
 * every seller's payout rows, newest first, with per-row adjustment sums
 * attached — the read the AdminPaymentsPage ledger section renders, so
 * the #390 mark-paid control has rows to act on. The endpoint shipped
 * with no UI because no ADMIN read existed; this test pins the read
 * itself. The mark-paid write stays covered by
 * {@link AdminPayoutMarkPaidAuditTest}.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link AdminPayoutMarkPaidAuditTest}: the rows must be committed by
 * the requests themselves to be asserted here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminPayoutListTest {

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
  private SellerPayoutAdjustmentRepository adjustments;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @AfterEach
  void cleanUp() {
    adjustments.deleteAll();
    payouts.deleteAll();
    orders.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User newUser(String role) {
    String tag = "payout-list-" + role + "-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private User newAdmin() {
    String tag = "payout-list-admin-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    User admin = new User(tag, tag + "@example.com", passwords.encode("password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    return users.save(admin);
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  /** A COMPLETED order with a payout row in the given status. */
  private SellerPayout seedPayout(User seller, User buyer, PayoutStatus status, long amountCents) {
    Item item = new Item("Payout thing " + UUID.randomUUID().toString().substring(0, 8),
        "seeded", amountCents, seller.getId());
    item.setStatus(ItemStatus.SOLD);
    items.save(item);
    Order order = new Order(item, buyer.getId(), amountCents);
    order.setStatus(OrderStatus.COMPLETED);
    orders.save(order);
    SellerPayout payout = new SellerPayout(order.getId(), seller.getId(), amountCents);
    payout.setStatus(status);
    return payouts.save(payout);
  }

  @Test
  void adminListsAllRows_newestFirst() throws Exception {
    User admin = newAdmin();
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    SellerPayout older = seedPayout(seller, buyer, PayoutStatus.PAID, 5000L);
    SellerPayout newer = seedPayout(seller, buyer, PayoutStatus.PAYABLE, 7000L);

    mockMvc.perform(get("/api/admin/payouts")
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(2))
        .andExpect(jsonPath("$.data.content[0].id").value(newer.getId()))
        .andExpect(jsonPath("$.data.content[0].orderId").value(newer.getOrderId()))
        .andExpect(jsonPath("$.data.content[0].sellerId").value(seller.getId()))
        .andExpect(jsonPath("$.data.content[0].amountCents").value(7000))
        .andExpect(jsonPath("$.data.content[0].netCents").value(7000))
        .andExpect(jsonPath("$.data.content[0].status").value("PAYABLE"))
        .andExpect(jsonPath("$.data.content[1].id").value(older.getId()))
        .andExpect(jsonPath("$.data.content[1].status").value("PAID"));
  }

  @Test
  void statusFilter_returnsOnlyThatStatus() throws Exception {
    User admin = newAdmin();
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    seedPayout(seller, buyer, PayoutStatus.PENDING, 5000L);
    SellerPayout payable = seedPayout(seller, buyer, PayoutStatus.PAYABLE, 7000L);
    seedPayout(seller, buyer, PayoutStatus.PAID, 9000L);

    mockMvc.perform(get("/api/admin/payouts")
            .header("Authorization", "Bearer " + token(admin))
            .param("status", "PAYABLE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].id").value(payable.getId()));
  }

  @Test
  void adjustmentSum_isReflectedInNet() throws Exception {
    User admin = newAdmin();
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    SellerPayout payout = seedPayout(seller, buyer, PayoutStatus.PAYABLE, 5000L);
    adjustments.save(new SellerPayoutAdjustment(payout.getId(), payout.getOrderId(),
        seller.getId(), -1500L, "dispute refund"));

    mockMvc.perform(get("/api/admin/payouts")
            .header("Authorization", "Bearer " + token(admin))
            .param("status", "PAYABLE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].amountCents").value(5000))
        .andExpect(jsonPath("$.data.content[0].adjustmentsCents").value(-1500))
        .andExpect(jsonPath("$.data.content[0].netCents").value(3500));
  }

  @Test
  void rowsFromOtherSellers_areVisibleToAdmin() throws Exception {
    User admin = newAdmin();
    User sellerOne = newUser("seller-one");
    User sellerTwo = newUser("seller-two");
    User buyer = newUser("buyer");
    SellerPayout one = seedPayout(sellerOne, buyer, PayoutStatus.PAYABLE, 5000L);
    SellerPayout two = seedPayout(sellerTwo, buyer, PayoutStatus.PAYABLE, 7000L);

    mockMvc.perform(get("/api/admin/payouts")
            .header("Authorization", "Bearer " + token(admin))
            .param("status", "PAYABLE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(2))
        .andExpect(jsonPath("$.data.content[0].sellerId").value(sellerTwo.getId()))
        .andExpect(jsonPath("$.data.content[1].sellerId").value(sellerOne.getId()));
  }

  @Test
  void unknownStatus_returns400() throws Exception {
    User admin = newAdmin();

    mockMvc.perform(get("/api/admin/payouts")
            .header("Authorization", "Bearer " + token(admin))
            .param("status", "BOGUS"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void nonAdmin_returns403() throws Exception {
    User seller = newUser("seller");

    mockMvc.perform(get("/api/admin/payouts")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isForbidden());
  }

  @Test
  void anonymous_returns401() throws Exception {
    mockMvc.perform(get("/api/admin/payouts"))
        .andExpect(status().isUnauthorized());
  }
}
