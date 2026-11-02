package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.order.PaymentWebhookService;
import com.toni.marketplace.order.WebhookEvent;
import com.toni.marketplace.order.WebhookEventRepository;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Rate-limit behavior of the ADMIN payment webhook replay through
 * {@code AuthRateLimitFilter} (backlog #305): {@code POST
 * /api/admin/payments/webhooks/{id}/replay} has its own per-<em>user</em>
 * bucket (production default 20/minute — pinned to 3/minute here so the
 * burst arithmetic stays small), keyed on the JWT principal id like
 * the message/report buckets, with the per-IP fallback for a
 * missing/invalid token. The bucket never resets on success — a
 * completed replay must not refill the allowance — and it is
 * deliberately separate from the #297 dispute/appeal resolution
 * bucket (a replay is not a case resolution), the #303 ADMIN
 * user-action bucket and the #304 ADMIN report-action bucket.
 *
 * <p>Deliberately NOT {@code @Transactional}, like
 * {@code AdminWebhookReplayTest}: the replay bookkeeping runs through
 * {@code WebhookEventRecorder}'s REQUIRES_NEW transactions, which
 * cannot see uncommitted fixtures — seeds commit for real and are
 * cleaned explicitly. Buckets are in-memory and survive across tests
 * within this class, so each test uses a fresh ADMIN (per-user keys),
 * mirroring {@code AdminReportActionRateLimitTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the ADMIN webhook-replay surface, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.admin-webhook-replay-attempts-per-minute=3",
    // Pin the neighbouring report-action bucket to the same small size so
    // the independence tests can exhaust it without many bogus calls.
    "app.auth.rate-limit.admin-report-action-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the bucket
    // assertions on the bogus replays below.
    "app.auth.login-lockout.enabled=false"})
class AdminWebhookReplayRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private WebhookEventRepository events;
  @Autowired private AuditLogRepository auditLog;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;

  @BeforeEach
  void cleanBefore() {
    cleanAll();
  }

  @AfterEach
  void cleanAfter() {
    cleanAll();
  }

  /** FK-safe order: audit rows precede event rows precede users. */
  private void cleanAll() {
    auditLog.deleteAll();
    events.deleteAll();
    users.deleteAll();
  }

  private User freshUser() {
    String tag = "adminreplay-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private User freshAdmin() {
    String tag = "adminreplay-admin-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    User admin = new User(tag, tag + "@example.com", passwords.encode("password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    return users.save(admin);
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private WebhookEvent freshMatchedEvent() {
    String tag = "adminreplay-evt-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return events.saveAndFlush(new WebhookEvent(tag,
        PaymentWebhookService.TYPE_CAPTURE_SUCCEEDED, "cap_" + tag,
        WebhookEvent.Outcome.MATCHED));
  }

  private void replay(String adminToken, long eventId, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/admin/payments/webhooks/{id}/replay", eventId)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().is(expectedStatus));
  }

  private void dismiss(String adminToken, long reportId, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/admin/reports/{id}/dismiss", reportId)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().is(expectedStatus));
  }

  @Test
  void singleLegitimateReplayPasses() throws Exception {
    WebhookEvent row = freshMatchedEvent();
    String adminToken = token(freshAdmin());
    mockMvc.perform(post("/api/admin/payments/webhooks/{id}/replay", row.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.eventId").value(row.getEventId()))
        .andExpect(jsonPath("$.data.outcome").value("MATCHED"))
        .andExpect(jsonPath("$.data.duplicate").value(true))
        .andExpect(jsonPath("$.data.applied").value(false))
        .andExpect(jsonPath("$.data.replayCount").value(1));
    assertThat(events.findById(row.getId()).orElseThrow().getReplayCount()).isEqualTo(1);
  }

  @Test
  void replayBurstIs429WithEnvelope() throws Exception {
    String adminToken = token(freshAdmin());
    // Three bogus replays consume the bucket (404: no event replayed).
    for (int i = 0; i < 3; i++) {
      replay(adminToken, 999_999_999L, 404);
    }
    // The bucket is empty: the fourth replay is throttled before the
    // controller ever sees it, so no replayCount is bumped.
    mockMvc.perform(post("/api/admin/payments/webhooks/{id}/replay", 999_999_999L)
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
  void replayBucketIsPerAdmin() throws Exception {
    String firstAdminToken = token(freshAdmin());
    for (int i = 0; i < 3; i++) {
      replay(firstAdminToken, 999_999_999L, 404);
    }
    replay(firstAdminToken, 999_999_999L, 429);

    replay(token(freshAdmin()), 999_999_999L, 404);
  }

  @Test
  void nonAdminSingleReplayIs403AndDoesNotStarveAdminBucket() throws Exception {
    // A non-ADMIN caller's single replay attempt still reaches the
    // security chain and answers 403 — the filter consumes one token
    // from that caller's own principal bucket on the way, but the 403
    // verdict is what the caller sees, not a 429.
    mockMvc.perform(post("/api/admin/payments/webhooks/{id}/replay", 999_999_999L)
            .header("Authorization", "Bearer " + token(freshUser())))
        .andExpect(status().isForbidden());
    // The ADMIN's bucket is untouched by that attempt: a bogus replay
    // by a fresh ADMIN still reaches the controller and answers 404.
    replay(token(freshAdmin()), 999_999_999L, 404);
  }

  @Test
  void replayBucketExhaustionLeavesReportActionBucketIntact() throws Exception {
    String adminToken = token(freshAdmin());
    for (int i = 0; i < 3; i++) {
      replay(adminToken, 999_999_999L, 404);
    }
    replay(adminToken, 999_999_999L, 429);
    // The #304 bucket is untouched: a bogus dismiss reaches the
    // controller and answers 404, not 429.
    dismiss(adminToken, 999_999L, 404);
  }

  @Test
  void reportActionBucketExhaustionLeavesReplayBucketIntact() throws Exception {
    String adminToken = token(freshAdmin());
    for (int i = 0; i < 3; i++) {
      dismiss(adminToken, 999_999L, 404);
    }
    dismiss(adminToken, 999_999L, 429);
    // The #305 bucket is untouched: a bogus replay reaches the
    // controller and answers 404, not 429.
    replay(adminToken, 999_999_999L, 404);
  }
}
