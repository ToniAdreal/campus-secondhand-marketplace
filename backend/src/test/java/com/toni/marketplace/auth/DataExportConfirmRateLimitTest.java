package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * Rate-limit behavior of {@code POST /api/users/me/export/confirm}
 * through {@code AuthRateLimitFilter} (backlog #311): the confirm step
 * mints the single-use export grant after a fresh proof (current
 * password, plus a second-factor code when 2FA is enabled), so it is
 * the password-plus-code guessing surface for a stolen Bearer token —
 * and it previously had no bucket at all, while only the {@code GET
 * /api/users/me/export} download itself sat in the data-export bucket.
 * The confirm now has its own per-<em>user</em> bucket (production
 * default 5/minute, like the step-up bucket), keyed on the JWT
 * principal id like the message/report buckets, with the per-IP
 * fallback for a missing/invalid token, never reset on success — a
 * minted grant must not refill a guesser's budget, and a throttled
 * confirm mints nothing.
 *
 * <p>The confirm bucket is pinned to 6/minute here (not 3) so the
 * independence tests can exhaust one sibling bucket while leaving
 * headroom in the other for the same user: a 3-download burst leaves
 * 3 confirm tokens, and a 6-confirm burst leaves all 3 download
 * tokens. The step-up and download buckets stay pinned to 3/minute
 * like their own test classes. Buckets are in-memory and survive
 * across tests within this class, so each test uses fresh users
 * (per-user keys), mirroring {@code AccountDataExportRateLimitTest}.
 * Every confirm uses a freshly minted token, because the confirm
 * requires a fresh credential proof (backlog #218) — reusing one
 * token across the burst would let the proof go stale and mask the
 * bucket assertions with 403s.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the export-confirm surface, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.export-confirm-attempts-per-minute=6",
    // Pin the neighbouring buckets small so the independence tests can
    // exhaust them without a long burst.
    "app.auth.rate-limit.step-up-attempts-per-minute=3",
    "app.auth.rate-limit.data-export-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the bucket
    // assertions.
    "app.auth.login-lockout.enabled=false"})
class DataExportConfirmRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;

  private User freshUser() {
    String tag = "expconf-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private String freshToken(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private static RequestPostProcessor fromIp(String clientIp) {
    return request -> {
      request.setRemoteAddr(clientIp);
      return request;
    };
  }

  /** One confirm with a freshly minted token; returns the minted grant. */
  private String confirmGrant(User user) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/users/me/export/confirm")
            .header("Authorization", "Bearer " + freshToken(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"password\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String grant = new com.fasterxml.jackson.databind.ObjectMapper()
        .readTree(result.getResponse().getContentAsString())
        .path("data").path("grant").asText();
    assertThat(grant).isNotBlank();
    return grant;
  }

  private void confirmExpect(User user, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/users/me/export/confirm")
            .header("Authorization", "Bearer " + freshToken(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"password\"}"))
        .andExpect(status().is(expectedStatus));
  }

  private void stepUpOnce(String token, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/auth/step-up")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"password\"}"))
        .andExpect(status().is(expectedStatus));
  }

  private void exportOnce(User user, String grant, int expectedStatus) throws Exception {
    mockMvc.perform(get("/api/users/me/export")
            .header("Authorization", "Bearer " + freshToken(user))
            .header(AccountDataExportController.EXPORT_GRANT_HEADER, grant))
        .andExpect(status().is(expectedStatus));
  }

  @Test
  void singleLegitimateConfirmPasses() throws Exception {
    User user = freshUser();
    String grant = confirmGrant(user);
    assertThat(grant).isNotBlank();
  }

  @Test
  void confirmBurstIs429WithEnvelope() throws Exception {
    User user = freshUser();
    for (int i = 0; i < 6; i++) {
      confirmGrant(user);
    }
    // The bucket is empty: the seventh confirm is throttled before the
    // controller ever sees it, so it mints no grant.
    mockMvc.perform(post("/api/users/me/export/confirm")
            .header("Authorization", "Bearer " + freshToken(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"password\"}"))
        .andExpect(status().isTooManyRequests())
        // 6/min refills one token per 10 s, but the six confirms
        // themselves take seconds (password verification per confirm),
        // so the exact Retry-After varies with burst duration — pin
        // only its presence, mirroring AccountIdentityRateLimitTest.
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
  void confirmBucketIsPerUser() throws Exception {
    User first = freshUser();
    for (int i = 0; i < 6; i++) {
      confirmGrant(first);
    }
    confirmExpect(first, 429);

    // A different principal has a full bucket of their own.
    confirmGrant(freshUser());
  }

  @Test
  void anonymousConfirmFallbackStillThrottles() throws Exception {
    // No token: the filter falls back to a per-IP bucket. The first six
    // anonymous confirms are answered 401 by the security chain (the
    // bucket still consumed each attempt); the seventh is short-circuited
    // 429 by the filter itself. A dedicated remote address keeps this
    // test's IP bucket away from every other test's.
    String ip = "203.0.113.77";
    for (int i = 0; i < 6; i++) {
      mockMvc.perform(post("/api/users/me/export/confirm")
              .with(fromIp(ip))
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"currentPassword\":\"password\"}"))
          .andExpect(status().isUnauthorized());
    }
    mockMvc.perform(post("/api/users/me/export/confirm")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"password\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"));
  }

  @Test
  void confirmBurstLeavesStepUpIntact() throws Exception {
    User user = freshUser();
    String token = freshToken(user);
    for (int i = 0; i < 6; i++) {
      confirmGrant(user);
    }
    confirmExpect(user, 429);
    // The #218 step-up bucket is untouched: a correct re-proof passes.
    stepUpOnce(token, 200);
  }

  @Test
  void stepUpBurstLeavesConfirmIntact() throws Exception {
    User user = freshUser();
    String token = freshToken(user);
    stepUpOnce(token, 200);
    stepUpOnce(token, 200);
    stepUpOnce(token, 200);
    stepUpOnce(token, 429);
    // The #311 bucket is untouched: a correct confirm passes.
    confirmGrant(user);
  }

  @Test
  void confirmBurstLeavesDownloadIntact() throws Exception {
    User user = freshUser();
    String grant = null;
    for (int i = 0; i < 6; i++) {
      grant = confirmGrant(user);
    }
    confirmExpect(user, 429);
    // The #172 download bucket is untouched: the latest grant still
    // downloads, even with an empty confirm bucket.
    exportOnce(user, grant, 200);
  }

  @Test
  void downloadBurstLeavesConfirmIntact() throws Exception {
    User user = freshUser();
    for (int i = 0; i < 3; i++) {
      exportOnce(user, confirmGrant(user), 200);
    }
    // The download bucket is empty (3 of 3 spent) while the confirm
    // bucket still holds 3 tokens: a fresh confirm passes and mints a
    // new grant — but the download it authorizes is still throttled,
    // proving the confirm did not refill the download bucket.
    String grant = confirmGrant(user);
    mockMvc.perform(get("/api/users/me/export")
            .header("Authorization", "Bearer " + freshToken(user))
            .header(AccountDataExportController.EXPORT_GRANT_HEADER, grant))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }
}
