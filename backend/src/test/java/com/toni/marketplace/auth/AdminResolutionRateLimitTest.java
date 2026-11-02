package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.dispute.DisputeEvidenceRepository;
import com.toni.marketplace.dispute.OrderDisputeRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.moderation.ListingReportRepository;
import com.toni.marketplace.moderation.ReportAppealRepository;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.order.IdempotencyKeyRepository;
import com.toni.marketplace.order.OrderRepository;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Rate-limit behavior of the ADMIN dispute/appeal resolutions through
 * the {@link AuthRateLimitFilter} (backlog #297): {@code POST
 * /api/admin/disputes/{id}/resolve-refund}, {@code POST
 * /api/admin/disputes/{id}/resolve-release}, {@code POST
 * /api/admin/appeals/{id}/uphold} and {@code POST
 * /api/admin/appeals/{id}/reject} share one per-<em>user</em> bucket
 * (production default 20/minute — pinned to 3/minute here so the
 * burst arithmetic stays small), keyed on the JWT principal id like
 * the message/report buckets, with the per-IP fallback for a
 * missing/invalid token. The bucket never resets on success — a
 * completed resolution must not refill the allowance — and it is
 * deliberately separate from the #282 creation bucket (filing a
 * dispute or an appeal stays there).
 *
 * <p>Buckets are in-memory and survive across tests within this class,
 * so each test uses a fresh ADMIN (per-user keys), mirroring
 * {@code SessionRevocationRateLimitTest}. Deliberately NOT
 * {@code @Transactional}, mirroring {@code OrderDisputeTest} /
 * {@code ReportAppealTest}: dispute and appeal rows are created
 * through the real API, and the test owns its data via its unique
 * usernames and wipes every table it touches in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the
    // one place that exercises the ADMIN resolution surfaces, so
    // re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.admin-resolution-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the
    // resolution-bucket assertions.
    "app.auth.login-lockout.enabled=false"})
class AdminResolutionRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;
  @Autowired private ItemRepository items;
  @Autowired private OrderRepository orders;
  @Autowired private IdempotencyKeyRepository keys;
  @Autowired private NotificationRepository notifications;
  @Autowired private AuditLogRepository auditLog;
  @Autowired private RefreshTokenRepository refreshTokens;
  @Autowired private OrderDisputeRepository disputes;
  @Autowired private DisputeEvidenceRepository disputeEvidence;
  @Autowired private ListingReportRepository reports;
  @Autowired private ReportAppealRepository appeals;

  @AfterEach
  void cleanUp() {
    disputeEvidence.deleteAll();
    appeals.deleteAll();
    reports.deleteAll();
    disputes.deleteAll();
    notifications.deleteAll();
    auditLog.deleteAll();
    keys.deleteAll();
    orders.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User freshUser() {
    String tag = "adminres-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private User freshAdmin() {
    String tag = "adminres-admin-" + SEQ.incrementAndGet() + "-"
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
            .header("Idempotency-Key", "adminres-" + UUID.randomUUID())
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

  private long openDispute(User buyer, long orderId) throws Exception {
    String body = mockMvc.perform(post("/api/orders/{id}/dispute", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"never arrived\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return ((Number) JsonPath.read(body, "$.data.id")).longValue();
  }

  /** Files a report and has an ADMIN action it: an ACTIONED report on a WITHDRAWN listing. */
  private long actionedReport(User reporter, User admin, Item item) throws Exception {
    String body = mockMvc.perform(post("/api/items/{id}/report", item.getId())
            .header("Authorization", "Bearer " + token(reporter))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"SCAM\",\"note\":\"looks suspicious\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    long reportId = ((Number) JsonPath.read(body, "$.data.id")).longValue();
    mockMvc.perform(post("/api/admin/reports/{id}/action", reportId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ACTIONED"));
    return reportId;
  }

  private long fileAppeal(User seller, long reportId) throws Exception {
    String body = mockMvc.perform(post("/api/seller/reports/{id}/appeal", reportId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"please reconsider\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return ((Number) JsonPath.read(body, "$.data.id")).longValue();
  }

  private void resolveRefund(String adminToken, long disputeId, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/admin/disputes/{id}/resolve-refund", disputeId)
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"resolutionNote\":\"Parcel never arrived; refunding the buyer.\"}"))
        .andExpect(status().is(expectedStatus));
  }

  private void uphold(String adminToken, long appealId, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/admin/appeals/{id}/uphold", appealId)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().is(expectedStatus));
  }

  @Test
  void disputeResolveBurstIs429() throws Exception {
    String adminToken = token(freshAdmin());

    // Three bogus resolutions consume the bucket (404: no dispute resolved).
    for (int i = 0; i < 3; i++) {
      resolveRefund(adminToken, 999_999L, 404);
    }
    // The bucket is empty: the fourth resolution is throttled before
    // the controller ever sees it.
    mockMvc.perform(post("/api/admin/disputes/{id}/resolve-refund", 999_999L)
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"resolutionNote\":\"Parcel never arrived; refunding the buyer.\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header -> {
          String retryAfter = header.getResponse().getHeader("Retry-After");
          if (retryAfter == null) {
            throw new AssertionError("missing Retry-After header on the 429");
          }
        })
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  void singleDisputeResolutionPasses() throws Exception {
    User seller = freshUser();
    User buyer = freshUser();
    User admin = freshAdmin();
    Item item = newItem(seller, 10_000L);
    long orderId = paidOrder(buyer, seller, item);
    long disputeId = openDispute(buyer, orderId);

    mockMvc.perform(post("/api/admin/disputes/{id}/resolve-refund", disputeId)
            .header("Authorization", "Bearer " + token(admin))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"resolutionNote\":\"Parcel never arrived; refunding the buyer.\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("RESOLVED_REFUND"));
    assertThat(items.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.AVAILABLE);
  }

  @Test
  void resolutionBucketIsSharedAcrossDisputeAndAppealShapes() throws Exception {
    String adminToken = token(freshAdmin());

    // Exhausting the bucket with dispute resolutions leaves an appeal
    // resolution throttled too — one bucket for the whole queue, not
    // one per endpoint. A throttled appeal attempt resolves nothing,
    // so a real appeal resolved by a fresh ADMIN still upholds below.
    for (int i = 0; i < 3; i++) {
      resolveRefund(adminToken, 999_999L, 404);
    }
    uphold(adminToken, 999_999L, 429);
  }

  @Test
  void singleAppealResolutionPasses() throws Exception {
    User seller = freshUser();
    User reporter = freshUser();
    User admin = freshAdmin();
    Item item = newItem(seller, 4200L);
    long reportId = actionedReport(reporter, admin, item);
    long appealId = fileAppeal(seller, reportId);

    mockMvc.perform(post("/api/admin/appeals/{id}/uphold", appealId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("UPHELD"))
        .andExpect(jsonPath("$.data.listingRestored").value(true));
    assertThat(items.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.AVAILABLE);
  }

  @Test
  void nonAdminSingleResolutionIs403() throws Exception {
    // A non-ADMIN caller's single resolution attempt still reaches
    // the security chain and answers 403 — the filter consumes one
    // token from that caller's own principal bucket on the way, but
    // the 403 verdict is what the caller sees, not a 429.
    String userToken = token(freshUser());
    mockMvc.perform(post("/api/admin/disputes/{id}/resolve-refund", 999_999L)
            .header("Authorization", "Bearer " + userToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"resolutionNote\":\"Parcel never arrived; refunding the buyer.\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void resolutionBucketIsPerAdmin() throws Exception {
    // Exhausting one ADMIN's bucket leaves another ADMIN's untouched,
    // and a non-ADMIN's traffic lands on its own principal key too.
    String firstAdminToken = token(freshAdmin());
    for (int i = 0; i < 3; i++) {
      resolveRefund(firstAdminToken, 999_999L, 404);
    }
    resolveRefund(firstAdminToken, 999_999L, 429);

    String secondAdminToken = token(freshAdmin());
    resolveRefund(secondAdminToken, 999_999L, 404);

    // The non-ADMIN's own bucket is separate as well: its single
    // attempt still answers 403, not the first ADMIN's 429.
    mockMvc.perform(post("/api/admin/disputes/{id}/resolve-refund", 999_999L)
            .header("Authorization", "Bearer " + token(freshUser()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"resolutionNote\":\"Parcel never arrived; refunding the buyer.\"}"))
        .andExpect(status().isForbidden());
  }
}
