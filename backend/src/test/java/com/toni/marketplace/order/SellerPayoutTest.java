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
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The seller payout ledger (backlog #221): creation in the completion
 * transaction, the dispute-window gate, the hourly sweep's
 * promote-then-pay discipline, dispute-refund compensation entries, and
 * seller isolation.
 *
 * <p>Deliberately NOT {@code @Transactional}: rows must be committed by
 * the requests themselves to be asserted here, and the sweep is driven
 * directly with a future instant (its {@code now} parameter) instead of
 * a shared test clock — promotion compares the order's completed instant
 * plus the #169 window against it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SellerPayoutTest {

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
  private SellerPayoutRepository payouts;

  @Autowired
  private SellerPayoutAdjustmentRepository adjustments;

  @Autowired
  private SellerPayoutService payoutService;

  @Autowired
  private OrderDisputeRepository disputes;

  @Autowired
  private NotificationRepository notifications;

  @Autowired
  private AuditLogRepository auditLog;

  // Both ends: @AfterEach alone leaves the FIRST test in a shared fork
  // exposed to payout rows completed by fork-mates (other
  // non-transactional API test classes) — the combined run failed
  // completionCreatesExactlyOneRow with count 3, the isolated run 5/5.
  @BeforeEach
  @AfterEach
  void cleanUp() {
    adjustments.deleteAll();
    payouts.deleteAll();
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
    String tag = "payout-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private User newAdmin() {
    String tag = "payout-admin-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
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

  /** Creates an order through the real API, pays it, returns id + handover code. */
  private long paidOrder(User buyer, Item item) throws Exception {
    String body = mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", "payout-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + "}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    long orderId = ((Number) JsonPath.read(body, "$.data.id")).longValue();
    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PAID"));
    return orderId;
  }

  private String payCode(User buyer, long orderId) throws Exception {
    // The pay response discloses the handover code exactly once; re-pay is
    // idempotent and returns the order without the code, so mint a fresh
    // one through the buyer regenerate endpoint instead.
    String body = mockMvc.perform(post("/api/orders/{id}/handover-code/regenerate", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return JsonPath.read(body, "$.data.handoverCode");
  }

  private void completeOrder(User seller, User buyer, long orderId) throws Exception {
    String code = payCode(buyer, orderId);
    mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"handoverCode\":\"" + code + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("COMPLETED"));
  }

  private void partialRefund(User seller, long orderId, long amountCents) throws Exception {
    mockMvc.perform(post("/api/orders/{id}/refund", orderId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amountCents\":" + amountCents + ",\"reason\":\"item damaged\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PAID"));
  }

  private long openDispute(User buyer, long orderId) throws Exception {
    String body = mockMvc.perform(post("/api/orders/{id}/dispute", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"item never arrived\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return ((Number) JsonPath.read(body, "$.data.id")).longValue();
  }

  private String payoutListJson(User seller) throws Exception {
    return mockMvc.perform(get("/api/seller/payouts")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
  }

  private String payoutTotalsJson(User seller) throws Exception {
    return mockMvc.perform(get("/api/seller/payouts/totals")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
  }

  @Test
  void completionCreatesExactlyOneRowWithNetAmount_recompleteCreatesNone() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long orderId = paidOrder(buyer, item);
    partialRefund(seller, orderId, 2_500L);
    completeOrder(seller, buyer, orderId);

    String list = payoutListJson(seller);
    assertThat((Integer) JsonPath.read(list, "$.data.totalElements")).isEqualTo(1);
    assertThat((Integer) JsonPath.read(list, "$.data.content[0].amountCents")).isEqualTo(7_500);
    assertThat((Integer) JsonPath.read(list, "$.data.content[0].adjustmentsCents")).isEqualTo(0);
    assertThat((Integer) JsonPath.read(list, "$.data.content[0].netCents")).isEqualTo(7_500);
    assertThat((String) JsonPath.read(list, "$.data.content[0].status")).isEqualTo("PENDING");

    // Idempotent re-complete: the order is already COMPLETED, so the
    // completion path returns before creation — still exactly one row.
    mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"handoverCode\":\"000000\"}"))
        .andExpect(status().isOk());
    assertThat(payouts.count()).isEqualTo(1);
  }

  @Test
  void sweepPromotesAfterWindowAndPaysOnlyOnTheNextRun_thenIdempotent() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long orderId = paidOrder(buyer, item);
    completeOrder(seller, buyer, orderId);
    Instant now = Instant.now();

    SellerPayoutService.PayoutSweepCounts first = payoutService.runSweep(now);
    assertThat(first.promoted()).isEqualTo(0);
    assertThat((String) JsonPath.read(payoutListJson(seller), "$.data.content[0].status"))
        .isEqualTo("PENDING");

    SellerPayoutService.PayoutSweepCounts second = payoutService.runSweep(now.plusSeconds(8L * 24 * 3600));
    assertThat(second.promoted()).isEqualTo(1);
    assertThat(second.paid()).isEqualTo(0);
    String afterPromote = payoutListJson(seller);
    assertThat((String) JsonPath.read(afterPromote, "$.data.content[0].status")).isEqualTo("PAYABLE");
    assertThat(JsonPath.<String>read(afterPromote, "$.data.content[0].payableAt")).isNotNull();
    assertThat(JsonPath.<String>read(afterPromote, "$.data.content[0].paidAt")).isNull();

    SellerPayoutService.PayoutSweepCounts third = payoutService.runSweep(now.plusSeconds(8L * 24 * 3600));
    assertThat(third.paid()).isEqualTo(1);
    String afterPay = payoutListJson(seller);
    assertThat((String) JsonPath.read(afterPay, "$.data.content[0].status")).isEqualTo("PAID");
    String paidAt = JsonPath.read(afterPay, "$.data.content[0].paidAt");

    SellerPayoutService.PayoutSweepCounts fourth = payoutService.runSweep(now.plusSeconds(9L * 24 * 3600));
    assertThat(fourth.promoted()).isEqualTo(0);
    assertThat(fourth.paid()).isEqualTo(0);
    String afterRerun = payoutListJson(seller);
    assertThat((String) JsonPath.read(afterRerun, "$.data.content[0].status")).isEqualTo("PAID");
    assertThat((String) JsonPath.read(afterRerun, "$.data.content[0].paidAt")).isEqualTo(paidAt);
    assertThat(payouts.count()).isEqualTo(1);
    assertThat(adjustments.count()).isEqualTo(0);
  }

  @Test
  void openDisputeBlocksPromotion_releaseUnblocksIt() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User admin = newAdmin();
    Item item = newItem(seller, 10_000L);
    long orderId = paidOrder(buyer, item);
    completeOrder(seller, buyer, orderId);
    long disputeId = openDispute(buyer, orderId);
    Instant later = Instant.now().plusSeconds(8L * 24 * 3600);

    assertThat(payoutService.runSweep(later).promoted()).isEqualTo(0);
    assertThat((String) JsonPath.read(payoutListJson(seller), "$.data.content[0].status"))
        .isEqualTo("PENDING");

    mockMvc.perform(post("/api/admin/disputes/{id}/resolve-release", disputeId)
            .header("Authorization", "Bearer " + token(admin))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"resolutionNote\":\"Payout sweep test release.\"}"))
        .andExpect(status().isOk());

    assertThat(payoutService.runSweep(later).promoted()).isEqualTo(1);
    assertThat((String) JsonPath.read(payoutListJson(seller), "$.data.content[0].status"))
        .isEqualTo("PAYABLE");
  }

  @Test
  void disputeRefundWritesNegativeAdjustment_settlementRowUnchanged_neverPaid() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User admin = newAdmin();
    Item item = newItem(seller, 10_000L);
    long orderId = paidOrder(buyer, item);
    completeOrder(seller, buyer, orderId);
    long disputeId = openDispute(buyer, orderId);

    mockMvc.perform(post("/api/admin/disputes/{id}/resolve-refund", disputeId)
            .header("Authorization", "Bearer " + token(admin))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"resolutionNote\":\"Payout sweep test refund.\"}"))
        .andExpect(status().isOk());

    String list = payoutListJson(seller);
    assertThat((Integer) JsonPath.read(list, "$.data.totalElements")).isEqualTo(1);
    assertThat((Integer) JsonPath.read(list, "$.data.content[0].amountCents")).isEqualTo(10_000);
    assertThat((Integer) JsonPath.read(list, "$.data.content[0].adjustmentsCents")).isEqualTo(-10_000);
    assertThat((Integer) JsonPath.read(list, "$.data.content[0].netCents")).isEqualTo(0);
    assertThat((String) JsonPath.read(list, "$.data.content[0].status")).isEqualTo("PENDING");

    String totals = payoutTotalsJson(seller);
    assertThat((Integer) JsonPath.read(totals, "$.data.pendingCents")).isEqualTo(10_000);
    assertThat((Integer) JsonPath.read(totals, "$.data.adjustmentsCents")).isEqualTo(-10_000);
    assertThat((Integer) JsonPath.read(totals, "$.data.netCents")).isEqualTo(0);

    // The order is REFUNDED now: the sweep must never promote or pay a
    // refunded order's row, even past the window.
    SellerPayoutService.PayoutSweepCounts sweep =
        payoutService.runSweep(Instant.now().plusSeconds(8L * 24 * 3600));
    assertThat(sweep.promoted()).isEqualTo(0);
    assertThat(sweep.paid()).isEqualTo(0);
    assertThat((String) JsonPath.read(payoutListJson(seller), "$.data.content[0].status"))
        .isEqualTo("PENDING");
  }

  @Test
  void sellerIsolationAndAnonymous401() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User stranger = newUser();
    Item item = newItem(seller, 10_000L);
    long orderId = paidOrder(buyer, item);
    completeOrder(seller, buyer, orderId);

    assertThat((Integer) JsonPath.read(payoutListJson(stranger), "$.data.totalElements")).isEqualTo(0);
    String strangerTotals = payoutTotalsJson(stranger);
    assertThat((Integer) JsonPath.read(strangerTotals, "$.data.netCents")).isEqualTo(0);

    mockMvc.perform(get("/api/seller/payouts")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/seller/payouts/totals")).andExpect(status().isUnauthorized());
  }
}
