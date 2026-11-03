package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
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
 * Rate-limit behavior of {@code POST /api/auth/logout} through
 * {@code AuthRateLimitFilter} (backlog #316): logout revokes the
 * caller's whole refresh-token family and bumps tokenVersion (backlog
 * #148), so a burst of logouts is session-kill spam — a holder of a
 * stolen access token can repeatedly kill the victim's family — and
 * it previously had no bucket at all. Logout now has its own
 * per-<em>user</em> bucket (production default 20/minute, like the
 * #282 bucket — pinned to 3/minute here so the burst arithmetic
 * stays small), keyed on the JWT principal id like the message /
 * report buckets, with the per-IP fallback for a missing/invalid
 * token, never reset on success — a completed logout must not
 * refill the allowance, and a throttled logout revokes nothing and
 * bumps no token version.
 *
 * <p>Buckets are in-memory and survive across tests within this
 * class, so each test uses fresh users (per-user keys), mirroring
 * {@code SessionRevocationRateLimitTest}. Every logout uses a
 * freshly minted token, reloaded from the repository first: logout
 * bumps tokenVersion, so a token minted from a stale user object
 * would 401 and mask the bucket assertions. The credential bucket
 * is pinned generously here so the independence test's login is
 * never masked by other tests' logins from the same IP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the logout surface, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.logout-attempts-per-minute=3",
    // Pin the neighbouring credential bucket generously so the
    // independence test's login is never the throttled one.
    "app.auth.rate-limit.attempts-per-minute=50",
    // Per-account lockout stays off so no other throttle masks the bucket
    // assertions on the login in the independence test.
    "app.auth.login-lockout.enabled=false"})
class LogoutRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();
  private static final AtomicInteger IP_SEQ = new AtomicInteger(700);

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;
  @Autowired private RefreshTokenRepository refreshTokens;

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

  private static RequestPostProcessor fromIp(String clientIp) {
    return request -> {
      request.setRemoteAddr(clientIp);
      return request;
    };
  }

  private User freshUser() {
    String tag = "logout-rl-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  /** A token minted from the user's current row, so it survives logout bumps. */
  private String freshToken(User user) {
    User current = users.findById(user.getId()).orElseThrow();
    return jwt.createTokenPair(current).accessToken();
  }

  /** A full pair minted from the user's current row. */
  private JwtTokenService.TokenPair freshPair(User user) {
    User current = users.findById(user.getId()).orElseThrow();
    return jwt.createTokenPair(current);
  }

  private long liveRefreshRows(User user) {
    return refreshTokens
        .findByUserIdAndRevokedFalseAndExpiresAtAfter(user.getId(), Instant.now())
        .size();
  }

  private long tokenVersion(User user) {
    return users.findById(user.getId()).orElseThrow().getTokenVersion();
  }

  private void logoutExpect(User user, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/auth/logout")
            .with(fromIp())
            .header("Authorization", "Bearer " + freshToken(user)))
        .andExpect(status().is(expectedStatus));
  }

  @Test
  void singleLegitimateLogoutPassesAndRevokesFamily() throws Exception {
    User user = freshUser();
    JwtTokenService.TokenPair pair = freshPair(user);
    long versionBefore = tokenVersion(user);
    assertThat(liveRefreshRows(user)).isEqualTo(1);

    MvcResult result = mockMvc.perform(post("/api/auth/logout")
            .with(fromIp())
            .header("Authorization", "Bearer " + pair.accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andReturn();
    String setCookie = result.getResponse().getHeader("Set-Cookie");
    assertThat(setCookie).contains("refresh_token=");
    assertThat(setCookie).contains("Max-Age=0");

    // The family is gone and the version bumped, so the pre-logout
    // access token 401s immediately (backlog #148).
    assertThat(liveRefreshRows(user)).isEqualTo(0);
    assertThat(tokenVersion(user)).isEqualTo(versionBefore + 1);
    mockMvc.perform(get("/api/auth/me")
            .with(fromIp())
            .header("Authorization", "Bearer " + pair.accessToken()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void logoutBurstIs429AndThrottledAttemptRevokesNothing() throws Exception {
    User user = freshUser();
    long versionBefore = tokenVersion(user);
    for (int i = 0; i < 3; i++) {
      logoutExpect(user, 200);
    }
    assertThat(tokenVersion(user)).isEqualTo(versionBefore + 3);
    assertThat(liveRefreshRows(user)).isEqualTo(0);

    // The bucket is empty: the fourth logout is throttled before the
    // controller ever sees it, so it revokes nothing and bumps no
    // version — the fresh pair behind it keeps rotating.
    JwtTokenService.TokenPair throttledPair = freshPair(user);
    mockMvc.perform(post("/api/auth/logout")
            .with(fromIp())
            .header("Authorization", "Bearer " + throttledPair.accessToken()))
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
    assertThat(tokenVersion(user)).isEqualTo(versionBefore + 3);
    assertThat(liveRefreshRows(user)).isEqualTo(1);
    mockMvc.perform(post("/api/auth/refresh")
            .with(fromIp())
            .cookie(new Cookie("refresh_token", throttledPair.refreshToken())))
        .andExpect(status().isOk());
  }

  @Test
  void logoutBucketIsPerUser() throws Exception {
    User first = freshUser();
    for (int i = 0; i < 3; i++) {
      logoutExpect(first, 200);
    }
    logoutExpect(first, 429);

    // A different principal has a full bucket of their own.
    logoutExpect(freshUser(), 200);
  }

  @Test
  void anonymousLogoutFallbackStillThrottles() throws Exception {
    // No token: the filter falls back to a per-IP bucket. The first three
    // anonymous logouts are answered 401 by the security chain (the
    // bucket still consumed each attempt); the fourth is short-circuited
    // 429 by the filter itself. A dedicated remote address keeps this
    // test's IP bucket away from every other test's.
    String anonymousIp = "203.0.113.91";
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(post("/api/auth/logout")
              .with(fromIp(anonymousIp)))
          .andExpect(status().isUnauthorized());
    }
    mockMvc.perform(post("/api/auth/logout")
            .with(fromIp(anonymousIp)))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"));
  }

  @Test
  void logoutBurstLeavesCredentialAndSessionRevocationIntact() throws Exception {
    User user = freshUser();
    String username = user.getUsername();
    for (int i = 0; i < 3; i++) {
      logoutExpect(user, 200);
    }
    logoutExpect(user, 429);

    // The credential bucket is untouched: the same user can still log
    // in from this IP, even with an empty logout bucket.
    mockMvc.perform(post("/api/auth/login")
            .with(fromIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + username + "\",\"password\":\"password\"}"))
        .andExpect(status().isOk());

    // The #296 session-revocation bucket is untouched too: a fresh
    // pair doubles as a step-up proof, and revoke-others passes.
    JwtTokenService.TokenPair pair = freshPair(user);
    mockMvc.perform(post("/api/auth/sessions/revoke-others")
            .with(fromIp())
            .header("Authorization", "Bearer " + pair.accessToken())
            .cookie(new Cookie("refresh_token", pair.refreshToken())))
        .andExpect(status().isOk());
  }
}
