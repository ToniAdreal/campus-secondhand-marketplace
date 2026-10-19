package com.toni.marketplace.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.dispute.DisputeStatus;
import com.toni.marketplace.dispute.OrderDispute;
import com.toni.marketplace.dispute.OrderDisputeRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.moderation.ListingReport;
import com.toni.marketplace.moderation.ListingReportRepository;
import com.toni.marketplace.moderation.ReportReason;
import com.toni.marketplace.moderation.ReportStatus;
import com.toni.marketplace.order.Order;
import com.toni.marketplace.order.OrderRepository;
import com.toni.marketplace.order.OrderStatus;
import com.toni.marketplace.order.WebhookEvent;
import com.toni.marketplace.order.WebhookEventRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN operations dashboard (backlog #196): GET /api/admin/dashboard —
 * ADMIN-only aggregate counts. Rows are seeded directly on the stores
 * (creation instants backdated through JDBC, mirroring
 * AdminSettlementTest) so the exact numbers pin the aggregate queries,
 * not the flows that normally produce the rows.
 *
 * <p>Seed accounting: {@code @BeforeEach} registers admin/seller/buyer
 * at "now", so every windowed user count includes those 3; no items,
 * orders, reports, disputes or webhook rows exist until a test seeds
 * them (each {@code seedOrder} also creates its listing, which the
 * listing counts below account for).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminDashboardTest {

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
  private ListingReportRepository reports;

  @Autowired
  private OrderDisputeRepository disputes;

  @Autowired
  private WebhookEventRepository webhooks;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private ObjectMapper objectMapper;

  private String adminToken;
  private String userToken;
  private User seller;
  private User buyer;
  private String tag;

  @BeforeEach
  void createUsersAndTokens() {
    tag = "dsh-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
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

  private User seedUserAt(Instant createdAt) {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    User user = users.save(new User(tag + "-u" + suffix, tag + "-u" + suffix + "@example.com",
        passwords.encode("seed-password")));
    jdbc.update("update app_user set created_at = ? where id = ?",
        Timestamp.from(createdAt), user.getId());
    return user;
  }

  private Item seedItemAt(ItemStatus status, Instant createdAt, Instant expiresAt) {
    Item item = new Item("Dashboard item " + UUID.randomUUID().toString().substring(0, 8),
        "seeded", 1_000L, seller.getId());
    item.setStatus(status);
    item.setExpiresAt(expiresAt);
    item = items.save(item);
    jdbc.update("update item set created_at = ? where id = ?",
        Timestamp.from(createdAt), item.getId());
    return item;
  }

  private Order seedOrderAt(OrderStatus status, String captureId, String refundId,
                            Instant createdAt) {
    Item item = seedItemAt(ItemStatus.AVAILABLE, createdAt, null);
    Order order = new Order(item, buyer.getId(), 1_000L);
    order.setStatus(status);
    order.setCaptureId(captureId);
    order.setRefundId(refundId);
    order = orders.save(order);
    jdbc.update("update orders set created_at = ? where id = ?",
        Timestamp.from(createdAt), order.getId());
    return order;
  }

  private void seedReport(long reporterId, long itemId, ReportStatus status) {
    ListingReport report = new ListingReport(reporterId, itemId, ReportReason.SPAM, null);
    report.setStatus(status);
    reports.save(report);
  }

  private void seedDispute(long orderId, DisputeStatus status) {
    OrderDispute dispute = new OrderDispute(orderId, buyer.getId(), seller.getId(),
        "seeded dispute", OrderStatus.PAID);
    dispute.setStatus(status);
    disputes.save(dispute);
  }

  private void seedWebhook(WebhookEvent.Outcome outcome, Instant createdAt) {
    WebhookEvent event = webhooks.save(new WebhookEvent(
        "evt_" + UUID.randomUUID().toString().replace("-", ""), "capture.succeeded",
        "cap_seed", outcome));
    jdbc.update("update webhook_event set created_at = ? where id = ?",
        Timestamp.from(createdAt), event.getId());
  }

  @Test
  void anonymousGets401Envelope() throws Exception {
    mockMvc.perform(get("/api/admin/dashboard"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void nonAdminGets403Envelope() throws Exception {
    mockMvc.perform(get("/api/admin/dashboard")
            .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
  }

  @Test
  void unknownWindowGets400Envelope() throws Exception {
    mockMvc.perform(get("/api/admin/dashboard").param("window", "yearly")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void omittedWindowDefaultsTo24h() throws Exception {
    mockMvc.perform(get("/api/admin/dashboard")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.window").value("24h"))
        // Only the three setup users exist, all registered just now.
        .andExpect(jsonPath("$.data.usersRegistered").value(3))
        .andExpect(jsonPath("$.data.ordersTotalInWindow").value(0))
        .andExpect(jsonPath("$.data.openReports").value(0));
  }

  @Test
  void seededCountsAreExact() throws Exception {
    Instant now = Instant.now();
    // Users: 2 more inside the 30d window, 1 far outside it.
    seedUserAt(now);
    seedUserAt(now.minus(Duration.ofDays(2)));
    seedUserAt(now.minus(Duration.ofDays(40)));

    // Listings (standalone; the orders below add 7 more AVAILABLE ones
    // created at their orders' instants — 6 now, 1 forty days ago).
    seedItemAt(ItemStatus.AVAILABLE, now, now.plus(Duration.ofDays(30)));
    seedItemAt(ItemStatus.AVAILABLE, now, now.plus(Duration.ofDays(30)));
    seedItemAt(ItemStatus.SOLD, now, now.plus(Duration.ofDays(30)));
    seedItemAt(ItemStatus.EXPIRED, now, now.minus(Duration.ofHours(2)));
    // Stored AVAILABLE but past its deadline: expired by the deadline
    // rule, and still counted in the stored-status AVAILABLE snapshot.
    seedItemAt(ItemStatus.AVAILABLE, now, now.minus(Duration.ofHours(2)));
    seedItemAt(ItemStatus.AVAILABLE, now.minus(Duration.ofDays(40)),
        now.minus(Duration.ofDays(40)));

    // Orders: one per status inside the window, plus one old PAID.
    Order disputed = seedOrderAt(OrderStatus.DISPUTED, "cap_disputed", null, now);
    seedOrderAt(OrderStatus.PAID, "cap_paid", null, now);
    seedOrderAt(OrderStatus.COMPLETED, "cap_completed", null, now);
    seedOrderAt(OrderStatus.REFUNDED, "cap_refunded", "rfd_refunded", now);
    seedOrderAt(OrderStatus.PENDING, null, null, now);
    seedOrderAt(OrderStatus.CANCELLED, null, null, now);
    Order oldPaid = seedOrderAt(OrderStatus.PAID, "cap_old", null, now.minus(Duration.ofDays(40)));

    // Moderation snapshots: current state, window-free.
    Item reported = seedItemAt(ItemStatus.AVAILABLE, now, now.plus(Duration.ofDays(30)));
    seedReport(buyer.getId(), reported.getId(), ReportStatus.OPEN);
    seedReport(seller.getId(), reported.getId(), ReportStatus.OPEN);
    seedReport(buyer.getId(), reported.getId(), ReportStatus.DISMISSED);
    seedDispute(disputed.getId(), DisputeStatus.OPEN);
    seedDispute(oldPaid.getId(), DisputeStatus.RESOLVED_REFUND);

    // Webhooks: 2 MATCHED + 1 MISMATCH inside, 1 MATCHED outside.
    seedWebhook(WebhookEvent.Outcome.MATCHED, now);
    seedWebhook(WebhookEvent.Outcome.MATCHED, now);
    seedWebhook(WebhookEvent.Outcome.MISMATCH, now);
    seedWebhook(WebhookEvent.Outcome.MATCHED, now.minus(Duration.ofDays(40)));

    mockMvc.perform(get("/api/admin/dashboard").param("window", "30d")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        // 3 setup users + 2 seeded inside; the 40-day-old one is out.
        .andExpect(jsonPath("$.data.usersRegistered").value(5))
        // Standalone 6 created now + 6 order listings created now;
        // the 40-day-old standalone and order listings are out.
        .andExpect(jsonPath("$.data.listingsCreated").value(12))
        // Stored AVAILABLE right now: standalone A, B, the past-deadline
        // one, the 40-day-old one and the reported one (5) + every
        // order's listing (7).
        .andExpect(jsonPath("$.data.listingsAvailableNow").value(12))
        // The EXPIRED row and the past-deadline AVAILABLE row; the
        // 40-day-old deadline fell outside the window.
        .andExpect(jsonPath("$.data.listingsExpiredInWindow").value(2))
        .andExpect(jsonPath("$.data.ordersTotalInWindow").value(6))
        .andExpect(jsonPath("$.data.ordersPending").value(1))
        .andExpect(jsonPath("$.data.ordersPaid").value(1))
        .andExpect(jsonPath("$.data.ordersCompleted").value(1))
        .andExpect(jsonPath("$.data.ordersCancelled").value(1))
        .andExpect(jsonPath("$.data.ordersRefunded").value(1))
        .andExpect(jsonPath("$.data.ordersDisputed").value(1))
        // PAID + COMPLETED + REFUNDED + DISPUTED carry capture ids.
        .andExpect(jsonPath("$.data.captures").value(4))
        .andExpect(jsonPath("$.data.refunds").value(1))
        .andExpect(jsonPath("$.data.openReports").value(2))
        .andExpect(jsonPath("$.data.openDisputes").value(1))
        .andExpect(jsonPath("$.data.webhooksMatched").value(2))
        .andExpect(jsonPath("$.data.webhooksMismatched").value(1));
  }

  @Test
  void windowBoundaryExcludesRowsJustOutside24h() throws Exception {
    Instant now = Instant.now();
    seedUserAt(now.minus(Duration.ofHours(25)));
    seedUserAt(now.minus(Duration.ofHours(1)));
    seedItemAt(ItemStatus.AVAILABLE, now.minus(Duration.ofHours(25)),
        now.plus(Duration.ofDays(30)));
    seedItemAt(ItemStatus.AVAILABLE, now.minus(Duration.ofHours(1)),
        now.plus(Duration.ofDays(30)));
    seedOrderAt(OrderStatus.PAID, "cap_edge_old", null, now.minus(Duration.ofHours(25)));
    seedOrderAt(OrderStatus.PENDING, null, null, now.minus(Duration.ofHours(1)));
    seedWebhook(WebhookEvent.Outcome.MATCHED, now.minus(Duration.ofHours(25)));
    seedWebhook(WebhookEvent.Outcome.MATCHED, now.minus(Duration.ofHours(1)));

    // 24h: only the 1-hour-old rows count (plus the 3 setup users);
    // the snapshot ignores the window entirely (4 AVAILABLE listings:
    // 2 standalone + 2 order listings, however old).
    mockMvc.perform(get("/api/admin/dashboard").param("window", "24h")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.usersRegistered").value(4))
        .andExpect(jsonPath("$.data.listingsCreated").value(2))
        .andExpect(jsonPath("$.data.listingsAvailableNow").value(4))
        .andExpect(jsonPath("$.data.ordersTotalInWindow").value(1))
        .andExpect(jsonPath("$.data.ordersPending").value(1))
        .andExpect(jsonPath("$.data.ordersPaid").value(0))
        .andExpect(jsonPath("$.data.captures").value(0))
        .andExpect(jsonPath("$.data.webhooksMatched").value(1));

    // 7d: the 25-hour-old rows join in.
    mockMvc.perform(get("/api/admin/dashboard").param("window", "7d")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.usersRegistered").value(5))
        .andExpect(jsonPath("$.data.listingsCreated").value(4))
        .andExpect(jsonPath("$.data.listingsAvailableNow").value(4))
        .andExpect(jsonPath("$.data.ordersTotalInWindow").value(2))
        .andExpect(jsonPath("$.data.ordersPaid").value(1))
        .andExpect(jsonPath("$.data.captures").value(1))
        .andExpect(jsonPath("$.data.webhooksMatched").value(2));
  }

  @Test
  void payloadKeySetIsExactlyTheDashboardContract() throws Exception {
    MvcResult result = mockMvc.perform(get("/api/admin/dashboard")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString())
        .path("data");
    Set<String> keys = new TreeSet<>();
    data.fieldNames().forEachRemaining(keys::add);
    // Counts only: no row data, no user identifiers, and no declines
    // figure (no durable source exists — see DashboardDto).
    assertThat(keys).isEqualTo(Set.of(
        "window", "windowStart",
        "usersRegistered", "listingsCreated", "listingsAvailableNow",
        "listingsExpiredInWindow",
        "ordersTotalInWindow", "ordersPending", "ordersPaid", "ordersCompleted",
        "ordersCancelled", "ordersRefunded", "ordersDisputed",
        "captures", "refunds",
        "openReports", "openDisputes",
        "webhooksMatched", "webhooksMismatched"));
  }
}
