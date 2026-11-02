package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.moderation.ListingReport;
import com.toni.marketplace.moderation.ListingReportRepository;
import com.toni.marketplace.moderation.ReportReason;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rate-limit behavior of the ADMIN report dismiss/action writes through
 * {@code AuthRateLimitFilter} (backlog #304): {@code POST
 * /api/admin/reports/{id}/dismiss} and {@code POST
 * /api/admin/reports/{id}/action} share one per-<em>user</em> bucket
 * (production default 20/minute — pinned to 3/minute here so the
 * burst arithmetic stays small), keyed on the JWT principal id like
 * the message/report buckets, with the per-IP fallback for a
 * missing/invalid token. The bucket never resets on success — a
 * completed dismiss or action must not refill the allowance — and it
 * is deliberately separate from the #297 dispute/appeal resolution
 * bucket (a different moderation queue) and the #303 ADMIN
 * user-action bucket.
 *
 * <p>Buckets are in-memory and survive across tests within this class,
 * so each test uses a fresh ADMIN (per-user keys), mirroring
 * {@code AdminUserActionRateLimitTest}. Rows are seeded
 * repository-direct, mirroring {@code AdminUserDisableTest}: a
 * dismiss needs only the report row, and an action additionally
 * withdraws its listing, so the legitimate-action test seeds a real
 * AVAILABLE item for the withdrawal assertion.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the ADMIN report-action surfaces, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.admin-report-action-attempts-per-minute=3",
    // Pin the neighbouring user-action bucket to the same small size so
    // the independence tests can exhaust it without many bogus calls.
    "app.auth.rate-limit.admin-user-action-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the bucket
    // assertions on the bogus actions below.
    "app.auth.login-lockout.enabled=false"})
class AdminReportActionRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private ItemRepository itemRepo;
  @Autowired private ListingReportRepository reports;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;

  private User freshUser() {
    String tag = "adminrep-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private User freshAdmin() {
    String tag = "adminrep-admin-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    User admin = new User(tag, tag + "@example.com", passwords.encode("password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    return users.save(admin);
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private ListingReport freshReport() {
    User reporter = freshUser();
    return reports.save(new ListingReport(reporter.getId(), 999_999L, ReportReason.SCAM,
        "looks suspicious"));
  }

  private void dismiss(String adminToken, long reportId, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/admin/reports/{id}/dismiss", reportId)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().is(expectedStatus));
  }

  private void action(String adminToken, long reportId, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/admin/reports/{id}/action", reportId)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().is(expectedStatus));
  }

  private void disable(String adminToken, long targetId, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/admin/users/{id}/disable", targetId)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().is(expectedStatus));
  }

  @Test
  void singleLegitimateDismissPasses() throws Exception {
    ListingReport report = freshReport();
    String adminToken = token(freshAdmin());
    mockMvc.perform(post("/api/admin/reports/{id}/dismiss", report.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(report.getId()))
        .andExpect(jsonPath("$.data.status").value("DISMISSED"));
    assertThat(reports.findById(report.getId()).orElseThrow().getStatus().name())
        .isEqualTo("DISMISSED");
  }

  @Test
  void singleLegitimateActionPassesAndWithdrawsListing() throws Exception {
    User seller = freshUser();
    Item item = itemRepo.save(new Item("Report-action seed", "seeded", 4200L, seller.getId()));
    User reporter = freshUser();
    ListingReport report = reports.save(
        new ListingReport(reporter.getId(), item.getId(), ReportReason.SCAM, "looks suspicious"));
    String adminToken = token(freshAdmin());
    mockMvc.perform(post("/api/admin/reports/{id}/action", report.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(report.getId()))
        .andExpect(jsonPath("$.data.status").value("ACTIONED"));
    assertThat(reports.findById(report.getId()).orElseThrow().getStatus().name())
        .isEqualTo("ACTIONED");
    assertThat(itemRepo.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.WITHDRAWN);
  }

  @Test
  void dismissBurstIs429WithEnvelope() throws Exception {
    String adminToken = token(freshAdmin());
    // Three bogus dismisses consume the bucket (404: no report dismissed).
    for (int i = 0; i < 3; i++) {
      dismiss(adminToken, 999_999L, 404);
    }
    // The bucket is empty: the fourth dismiss is throttled before the
    // controller ever sees it.
    mockMvc.perform(post("/api/admin/reports/{id}/dismiss", 999_999L)
            .header("Authorization", "Bearer " + adminToken))
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
  void actionBucketIsSharedAcrossDismissAndAction() throws Exception {
    // One bogus call per shape exhausts the shared bucket — one bucket
    // for the whole report-action surface, not one per endpoint.
    String adminToken = token(freshAdmin());
    dismiss(adminToken, 999_999L, 404);
    action(adminToken, 999_999L, 404);
    dismiss(adminToken, 999_999L, 404);
    // The fourth action, on either shape, is throttled.
    action(adminToken, 999_999L, 429);
  }

  @Test
  void actionBucketIsPerAdmin() throws Exception {
    String firstAdminToken = token(freshAdmin());
    for (int i = 0; i < 3; i++) {
      dismiss(firstAdminToken, 999_999L, 404);
    }
    dismiss(firstAdminToken, 999_999L, 429);

    dismiss(token(freshAdmin()), 999_999L, 404);
  }

  @Test
  void nonAdminSingleActionIs403AndDoesNotStarveAdminBucket() throws Exception {
    // A non-ADMIN caller's single action attempt still reaches the
    // security chain and answers 403 — the filter consumes one token
    // from that caller's own principal bucket on the way, but the 403
    // verdict is what the caller sees, not a 429.
    mockMvc.perform(post("/api/admin/reports/{id}/dismiss", 999_999L)
            .header("Authorization", "Bearer " + token(freshUser())))
        .andExpect(status().isForbidden());
    // The ADMIN's bucket is untouched by that attempt: a bogus dismiss
    // by a fresh ADMIN still reaches the controller and answers 404.
    dismiss(token(freshAdmin()), 999_999L, 404);
  }

  @Test
  void actionBucketExhaustionLeavesAdminUserActionBucketIntact() throws Exception {
    String adminToken = token(freshAdmin());
    for (int i = 0; i < 3; i++) {
      dismiss(adminToken, 999_999L, 404);
    }
    dismiss(adminToken, 999_999L, 429);
    // The #303 bucket is untouched: a bogus disable reaches the
    // controller and answers 404, not 429.
    disable(adminToken, 999_999L, 404);
  }

  @Test
  void adminUserActionBucketExhaustionLeavesActionBucketIntact() throws Exception {
    String adminToken = token(freshAdmin());
    for (int i = 0; i < 3; i++) {
      disable(adminToken, 999_999L, 404);
    }
    disable(adminToken, 999_999L, 429);
    // The #304 bucket is untouched: a bogus dismiss reaches the
    // controller and answers 404, not 429.
    dismiss(adminToken, 999_999L, 404);
  }
}
