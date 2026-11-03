package com.toni.marketplace.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toni.marketplace.auth.AccountDataExportController;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rate-limit behavior of {@code GET /api/admin/audit-log/export}
 * through {@code AuthRateLimitFilter} (backlog #318): the ADMIN
 * audit-log CSV export previously had no bucket at all, while the
 * #172 account-data export and the #317 seller sales export did.
 * The export scans the audit table, so it now has its own
 * per-<em>ADMIN</em> bucket (production default 10/minute, per the
 * backlog #318 example — pinned to 3/minute here so the burst
 * arithmetic stays small), keyed on the JWT principal id like the
 * message / report buckets, with the per-IP fallback for a
 * missing/invalid token, never reset on success — a completed
 * export must not refill the allowance, and a throttled export
 * streams nothing.
 *
 * <p>Buckets are in-memory and survive across tests within this
 * class, so each test uses fresh admins (per-ADMIN keys), mirroring
 * {@code SellerSalesExportRateLimitTest}. The export body is
 * asserted by its CSV header prefix, not by exact rows: audit rows
 * from other test classes sharing this context must not leak into
 * the assertions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the audit-export surface, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.audit-log-export-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the bucket
    // assertions.
    "app.auth.login-lockout.enabled=false"})
class AuditLogExportRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;

  private User freshAdmin() {
    String tag = "auditexp-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    User admin = new User(tag, tag + "@example.com", passwords.encode("password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    return users.save(admin);
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private static RequestPostProcessor fromIp(String clientIp) {
    return request -> {
      request.setRemoteAddr(clientIp);
      return request;
    };
  }

  private void exportOnce(User admin) throws Exception {
    // The export streams via StreamingResponseBody, so (mirroring
    // AuditLogExportTest) the request must go through async dispatch:
    // a plain perform can read the response before the stream flushes
    // and see an empty body, which grouped runs exposed as "" instead
    // of the CSV header. The 429/401 paths below never reach the
    // controller, so they stay plain performs (no async is started).
    MvcResult started = mockMvc.perform(get("/api/admin/audit-log/export")
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(request().asyncStarted())
        .andReturn();
    String body = mockMvc.perform(asyncDispatch(started))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/csv"))
        .andReturn().getResponse().getContentAsString();
    assertThat(body).startsWith(AuditLogCsv.HEADER + "\r\n");
  }

  private void exportOverLimit(User admin) throws Exception {
    mockMvc.perform(get("/api/admin/audit-log/export")
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }

  /** One #172 account-data export, via its step-up grant flow. */
  private void accountExportOnce(User user) throws Exception {
    String confirmBody = mockMvc.perform(
            post("/api/users/me/export/confirm")
                .header("Authorization", "Bearer " + token(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"password\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    String grant = new ObjectMapper()
        .readTree(confirmBody).path("data").path("grant").asText();
    mockMvc.perform(get("/api/users/me/export")
            .header("Authorization", "Bearer " + token(user))
            .header(AccountDataExportController.EXPORT_GRANT_HEADER, grant))
        .andExpect(status().isOk());
  }

  @Test
  void singleLegitimateExportPassesWithCsvBody() throws Exception {
    exportOnce(freshAdmin());
  }

  @Test
  void exportBurstIs429WithEnvelopeAndRetryAfter() throws Exception {
    User admin = freshAdmin();
    for (int i = 0; i < 3; i++) {
      exportOnce(admin);
    }
    // The bucket is empty; the security chain is never reached.
    mockMvc.perform(get("/api/admin/audit-log/export")
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isTooManyRequests())
        // 3/min = one token per 20 s.
        .andExpect(header().string("Retry-After", "20"))
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  void exportBucketIsPerAdmin() throws Exception {
    User spammer = freshAdmin();
    for (int i = 0; i < 3; i++) {
      exportOnce(spammer);
    }
    exportOverLimit(spammer);
    // A different ADMIN principal has a full bucket of their own.
    exportOnce(freshAdmin());
  }

  @Test
  void exportBurstLeavesAuditListAndUserExportIntact() throws Exception {
    User admin = freshAdmin();
    for (int i = 0; i < 3; i++) {
      exportOnce(admin);
    }
    exportOverLimit(admin);

    // The audit-list GET is an unthrottled read, not an export.
    mockMvc.perform(get("/api/admin/audit-log")
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
    // The #172 account-data-export bucket is untouched: the same
    // ADMIN can still pull their own data document.
    accountExportOnce(admin);
    // And the audit-export bucket is still empty afterwards.
    exportOverLimit(admin);
  }

  @Test
  void anonymousExportFallbackStillThrottles() throws Exception {
    // No token: the filter falls back to a per-IP bucket. The first three
    // anonymous exports are answered 401 by the security chain (the
    // bucket still consumed each attempt); the fourth is short-circuited
    // 429 by the filter itself. A dedicated remote address keeps this
    // test's IP bucket away from every other test's.
    String anonymousIp = "203.0.113.93";
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(get("/api/admin/audit-log/export")
              .with(fromIp(anonymousIp)))
          .andExpect(status().isUnauthorized());
    }
    mockMvc.perform(get("/api/admin/audit-log/export")
            .with(fromIp(anonymousIp)))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"));
  }
}
