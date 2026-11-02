package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
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
 * Rate-limit behavior of the session and trusted-device revocation writes
 * through the {@link AuthRateLimitFilter} (backlog #296): {@code DELETE
 * /api/auth/sessions/{id}}, {@code POST
 * /api/auth/sessions/revoke-others} and {@code DELETE
 * /api/auth/trusted-devices/{id}} share one per-<em>user</em> bucket
 * (production default 5/minute, like the step-up bucket — pinned to
 * 3/minute here so the burst arithmetic stays small), keyed on the JWT
 * principal id like the step-up/message buckets, never reset on success.
 * A throttled attempt never reaches the controller, so it revokes
 * nothing — the caller's own sessions and trusted devices stay intact —
 * and revocation traffic must not consume the step-up or credential
 * buckets (revoke-others is step-up gated, but the gate proves the
 * proof is fresh, not that the rate is sane).
 *
 * <p>Buckets are in-memory and survive across tests within this class,
 * so each test uses a fresh user (per-user keys) and a fresh client IP
 * (for the per-IP refresh/credential buckets the setup and assertions
 * touch), mirroring {@code PasskeyChallengeRateLimitTest}. Users and
 * sessions are built repository-direct: {@code JwtTokenService}
 * .createTokenPair persists a real refresh family and mints an access
 * token whose auth-time is now, so it doubles as a fresh step-up proof
 * for the revoke-others shape.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the
    // one place that exercises the revocation surfaces, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.session-revocation-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the
    // revocation-bucket assertions.
    "app.auth.login-lockout.enabled=false"
})
class SessionRevocationRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();
  private static final AtomicInteger IP_SEQ = new AtomicInteger(600);

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;
  @Autowired private TrustedDeviceRepository trustedDevices;

  private String ip;

  @BeforeEach
  void setUp() {
    ip = "10.206.0." + IP_SEQ.getAndIncrement();
  }

  private RequestPostProcessor fromIp() {
    return request -> {
      request.setRemoteAddr(ip);
      return request;
    };
  }

  private User freshUser() {
    String tag = "sessrev-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private Cookie refreshCookie(String rawRefreshToken) {
    return new Cookie("refresh_token", rawRefreshToken);
  }

  /** The session id of the non-current entry when presenting {@code cookie}. */
  @SuppressWarnings("unchecked")
  private String otherSessionId(String access, Cookie cookie) throws Exception {
    MvcResult listed = mockMvc.perform(get("/api/auth/sessions")
            .with(fromIp())
            .header("Authorization", "Bearer " + access)
            .cookie(cookie))
        .andExpect(status().isOk())
        .andReturn();
    List<Map<String, Object>> sessions =
        JsonPath.read(listed.getResponse().getContentAsString(), "$.data");
    return sessions.stream()
        .filter(s -> !Boolean.TRUE.equals(s.get("current")))
        .findFirst().orElseThrow()
        .get("id").toString();
  }

  private void deleteSession(String access, String sessionId, int expectedStatus) throws Exception {
    mockMvc.perform(delete("/api/auth/sessions/" + sessionId)
            .with(fromIp())
            .header("Authorization", "Bearer " + access))
        .andExpect(status().is(expectedStatus));
  }

  private void deleteTrustedDevice(String access, long deviceId, int expectedStatus)
      throws Exception {
    mockMvc.perform(delete("/api/auth/trusted-devices/" + deviceId)
            .with(fromIp())
            .header("Authorization", "Bearer " + access))
        .andExpect(status().is(expectedStatus));
  }

  private void revokeOthers(String access, Cookie cookie, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/auth/sessions/revoke-others")
            .with(fromIp())
            .header("Authorization", "Bearer " + access)
            .cookie(cookie))
        .andExpect(status().is(expectedStatus));
  }

  private void refreshExpect(Cookie cookie, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/auth/refresh")
            .with(fromIp())
            .cookie(cookie))
        .andExpect(status().is(expectedStatus));
  }

  private TrustedDevice saveTrustedDevice(long userId) {
    return trustedDevices.save(new TrustedDevice(userId,
        JwtTokenService.sha256Hex("trusted-" + UUID.randomUUID()),
        "device", "127.0.0.1", Instant.now().plus(30, ChronoUnit.DAYS)));
  }

  private int trustedDeviceCount(String access) throws Exception {
    MvcResult listed = mockMvc.perform(get("/api/auth/trusted-devices")
            .with(fromIp())
            .header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andReturn();
    List<?> rows = JsonPath.read(listed.getResponse().getContentAsString(), "$.data");
    return rows.size();
  }

  @Test
  void sessionDeleteBurstIs429AndLeavesSessionsIntact() throws Exception {
    User user = freshUser();
    JwtTokenService.TokenPair first = jwt.createTokenPair(user);
    JwtTokenService.TokenPair second = jwt.createTokenPair(user);

    // Three bogus deletes consume the bucket (404: no session revoked).
    for (int i = 0; i < 3; i++) {
      deleteSession(first.accessToken(), "does-not-exist", 404);
    }
    // The bucket is empty: the fourth delete is throttled before the
    // controller ever sees it.
    mockMvc.perform(delete("/api/auth/sessions/does-not-exist")
            .with(fromIp())
            .header("Authorization", "Bearer " + first.accessToken()))
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

    // Neither session was touched by the burst or the throttled attempt.
    refreshExpect(refreshCookie(first.refreshToken()), 200);
    refreshExpect(refreshCookie(second.refreshToken()), 200);
  }

  @Test
  void singleSessionDeletePasses() throws Exception {
    User user = freshUser();
    JwtTokenService.TokenPair first = jwt.createTokenPair(user);
    JwtTokenService.TokenPair second = jwt.createTokenPair(user);
    Cookie cookieA = refreshCookie(first.refreshToken());
    Cookie cookieB = refreshCookie(second.refreshToken());

    String otherFamily = otherSessionId(first.accessToken(), cookieA);
    deleteSession(first.accessToken(), otherFamily, 200);

    // The other session's refresh token is dead…
    refreshExpect(cookieB, 401);
    // …while the caller's session still rotates normally.
    refreshExpect(cookieA, 200);
  }

  @Test
  void revokeOthersBurstIs429AndKeepsCurrentSession() throws Exception {
    User user = freshUser();
    JwtTokenService.TokenPair first = jwt.createTokenPair(user);
    JwtTokenService.TokenPair second = jwt.createTokenPair(user);
    Cookie cookieA = refreshCookie(first.refreshToken());
    Cookie cookieB = refreshCookie(second.refreshToken());

    // Three legitimate revoke-others calls: the first revokes the one
    // other family, the next two revoke nothing — all pass.
    revokeOthers(second.accessToken(), cookieB, 200);
    revokeOthers(second.accessToken(), cookieB, 200);
    revokeOthers(second.accessToken(), cookieB, 200);
    // The bucket is empty: the fourth is throttled.
    revokeOthers(second.accessToken(), cookieB, 429);

    // The throttled attempt revoked nothing: the current session still
    // rotates, and the already-revoked other family stays dead.
    refreshExpect(cookieB, 200);
    refreshExpect(cookieA, 401);
  }

  @Test
  void trustedDeviceDeleteBurstIs429AndKeepsDevice() throws Exception {
    User user = freshUser();
    JwtTokenService.TokenPair pair = jwt.createTokenPair(user);
    saveTrustedDevice(user.getId());
    assertThat(trustedDeviceCount(pair.accessToken())).isEqualTo(1);

    // Three bogus deletes consume the bucket (404: no device revoked).
    for (int i = 0; i < 3; i++) {
      deleteTrustedDevice(pair.accessToken(), 987654321L, 404);
    }
    // The bucket is empty: the fourth delete is throttled.
    mockMvc.perform(delete("/api/auth/trusted-devices/987654321")
            .with(fromIp())
            .header("Authorization", "Bearer " + pair.accessToken()))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));

    // The throttled attempt revoked nothing: the device row survives.
    assertThat(trustedDeviceCount(pair.accessToken())).isEqualTo(1);
  }

  @Test
  void singleTrustedDeviceDeletePasses() throws Exception {
    User user = freshUser();
    JwtTokenService.TokenPair pair = jwt.createTokenPair(user);
    TrustedDevice device = saveTrustedDevice(user.getId());
    assertThat(trustedDeviceCount(pair.accessToken())).isEqualTo(1);

    deleteTrustedDevice(pair.accessToken(), device.getId(), 200);
    assertThat(trustedDeviceCount(pair.accessToken())).isZero();
  }

  @Test
  void revocationBucketDoesNotConsumeStepUpOrCredentialBuckets() throws Exception {
    User user = freshUser();
    JwtTokenService.TokenPair pair = jwt.createTokenPair(user);
    String username = user.getUsername();

    for (int i = 0; i < 3; i++) {
      deleteSession(pair.accessToken(), "does-not-exist", 404);
    }
    // The revocation bucket is empty, but the step-up bucket is its
    // own: a re-proof from the same user reaches the controller and
    // passes, not 429.
    mockMvc.perform(post("/api/auth/step-up")
            .with(fromIp())
            .header("Authorization", "Bearer " + pair.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"password\"}"))
        .andExpect(status().isOk());
    // And the credential bucket is untouched too: the same user can
    // still log in from this IP.
    mockMvc.perform(post("/api/auth/login")
            .with(fromIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + username + "\",\"password\":\"password\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void bucketIsPerUser() throws Exception {
    // Exhausting one user's bucket leaves another user's untouched.
    User first = freshUser();
    JwtTokenService.TokenPair firstPair = jwt.createTokenPair(first);
    for (int i = 0; i < 3; i++) {
      deleteSession(firstPair.accessToken(), "does-not-exist", 404);
    }
    deleteSession(firstPair.accessToken(), "does-not-exist", 429);

    User second = freshUser();
    JwtTokenService.TokenPair secondPair = jwt.createTokenPair(second);
    deleteSession(secondPair.accessToken(), "does-not-exist", 404);
  }
}
