package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rate-limit behavior of the ADMIN user actions through
 * {@code AuthRateLimitFilter} (backlog #303): {@code POST
 * /api/admin/users/{id}/disable}, {@code POST
 * /api/admin/users/{id}/enable} and {@code POST
 * /api/admin/users/{id}/totp-reset} share one per-<em>user</em> bucket
 * (production default 20/minute — pinned to 3/minute here so the
 * burst arithmetic stays small), keyed on the JWT principal id like
 * the message/report buckets, with the per-IP fallback for a
 * missing/invalid token. The bucket never resets on success — a
 * completed disable, enable or TOTP reset must not refill the
 * allowance — and it is deliberately separate from the #297
 * dispute/appeal resolution bucket (account action and case
 * resolution are different abuse shapes).
 *
 * <p>Buckets are in-memory and survive across tests within this class,
 * so each test uses a fresh ADMIN (per-user keys), mirroring
 * {@code AdminResolutionRateLimitTest}. Rows are seeded
 * repository-direct, mirroring {@code AdminUserDisableTest} /
 * {@code AdminTotpResetTest}: a TOTP-reset target carries a secret
 * and the enabled flag, so the reset exercises exactly the
 * controller/service path the throttle guards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the ADMIN user-action surfaces, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.admin-user-action-attempts-per-minute=3",
    // Pin the neighbouring resolution bucket to the same small size so
    // the independence tests can exhaust it without many bogus calls.
    "app.auth.rate-limit.admin-resolution-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the bucket
    // assertions on the bogus actions below.
    "app.auth.login-lockout.enabled=false"})
class AdminUserActionRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;

  private User freshUser() {
    String tag = "adminact-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private User freshAdmin() {
    String tag = "adminact-admin-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    User admin = new User(tag, tag + "@example.com", passwords.encode("password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    return users.save(admin);
  }

  private User freshTotpUser() {
    User victim = freshUser();
    victim.setTotpSecret("adminact-victim-secret");
    victim.setTotpEnabled(true);
    return users.save(victim);
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private void disable(String adminToken, long targetId, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/admin/users/{id}/disable", targetId)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().is(expectedStatus));
  }

  private void enable(String adminToken, long targetId, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/admin/users/{id}/enable", targetId)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().is(expectedStatus));
  }

  private void totpReset(String adminToken, long targetId, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/admin/users/{id}/totp-reset", targetId)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().is(expectedStatus));
  }

  private void resolveRefund(String adminToken, long disputeId, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/admin/disputes/{id}/resolve-refund", disputeId)
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"resolutionNote\":\"Parcel never arrived; refunding the buyer.\"}"))
        .andExpect(status().is(expectedStatus));
  }

  @Test
  void singleLegitimateDisablePasses() throws Exception {
    User victim = freshUser();
    String adminToken = token(freshAdmin());
    mockMvc.perform(post("/api/admin/users/{id}/disable", victim.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(victim.getId()))
        .andExpect(jsonPath("$.data.disabled").value(true));
    assertThat(users.findById(victim.getId()).orElseThrow().isDisabled()).isTrue();
  }

  @Test
  void singleLegitimateEnablePasses() throws Exception {
    User victim = freshUser();
    String adminToken = token(freshAdmin());
    disable(adminToken, victim.getId(), 200);
    assertThat(users.findById(victim.getId()).orElseThrow().isDisabled()).isTrue();
    mockMvc.perform(post("/api/admin/users/{id}/enable", victim.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.disabled").value(false));
    assertThat(users.findById(victim.getId()).orElseThrow().isDisabled()).isFalse();
  }

  @Test
  void singleLegitimateTotpResetPasses() throws Exception {
    User victim = freshTotpUser();
    String adminToken = token(freshAdmin());
    mockMvc.perform(post("/api/admin/users/{id}/totp-reset", victim.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totpEnabled").value(false));
    User after = users.findById(victim.getId()).orElseThrow();
    assertThat(after.isTotpEnabled()).isFalse();
    assertThat(after.getTotpSecret()).isNull();
  }

  @Test
  void disableBurstIs429WithEnvelope() throws Exception {
    String adminToken = token(freshAdmin());
    // Three bogus disables consume the bucket (404: no user disabled).
    for (int i = 0; i < 3; i++) {
      disable(adminToken, 999_999L, 404);
    }
    // The bucket is empty: the fourth disable is throttled before the
    // controller ever sees it.
    mockMvc.perform(post("/api/admin/users/{id}/disable", 999_999L)
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
  void actionBucketIsSharedAcrossDisableEnableAndTotpReset() throws Exception {
    // One bogus call per shape exhausts the shared bucket — one bucket
    // for the whole account-action surface, not one per endpoint.
    String adminToken = token(freshAdmin());
    disable(adminToken, 999_999L, 404);
    enable(adminToken, 999_999L, 404);
    totpReset(adminToken, 999_999L, 404);
    // The fourth action, on any shape, is throttled.
    disable(adminToken, 999_999L, 429);
  }

  @Test
  void actionBucketIsPerAdmin() throws Exception {
    String firstAdminToken = token(freshAdmin());
    for (int i = 0; i < 3; i++) {
      disable(firstAdminToken, 999_999L, 404);
    }
    disable(firstAdminToken, 999_999L, 429);

    disable(token(freshAdmin()), 999_999L, 404);
  }

  @Test
  void nonAdminSingleActionIs403AndDoesNotStarveAdminBucket() throws Exception {
    // A non-ADMIN caller's single action attempt still reaches the
    // security chain and answers 403 — the filter consumes one token
    // from that caller's own principal bucket on the way, but the 403
    // verdict is what the caller sees, not a 429.
    mockMvc.perform(post("/api/admin/users/{id}/disable", 999_999L)
            .header("Authorization", "Bearer " + token(freshUser())))
        .andExpect(status().isForbidden());
    // The ADMIN's bucket is untouched by that attempt: a bogus disable
    // by a fresh ADMIN still reaches the controller and answers 404.
    disable(token(freshAdmin()), 999_999L, 404);
  }

  @Test
  void actionBucketExhaustionLeavesAdminResolutionBucketIntact() throws Exception {
    String adminToken = token(freshAdmin());
    for (int i = 0; i < 3; i++) {
      disable(adminToken, 999_999L, 404);
    }
    disable(adminToken, 999_999L, 429);
    // The #297 bucket is untouched: a bogus resolution reaches the
    // controller and answers 404, not 429.
    resolveRefund(adminToken, 999_999L, 404);
  }

  @Test
  void adminResolutionBucketExhaustionLeavesActionBucketIntact() throws Exception {
    String adminToken = token(freshAdmin());
    for (int i = 0; i < 3; i++) {
      resolveRefund(adminToken, 999_999L, 404);
    }
    resolveRefund(adminToken, 999_999L, 429);
    // The #303 bucket is untouched: a bogus disable reaches the
    // controller and answers 404, not 429.
    disable(adminToken, 999_999L, 404);
  }
}
