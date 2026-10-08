package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rate-limit behavior of {@code POST /api/auth/refresh} through the
 * {@link AuthRateLimitFilter} — the refresh endpoint's own bucket
 * (default 30/minute per IP), separate from the credential bucket.
 *
 * <p>The application {@code Clock} bean is replaced with a {@link MutableClock}
 * so refill timing is deterministic. Each test uses a fresh client IP (the
 * limiter buckets are in-memory and survive across tests within this class).
 * A valid refresh cookie comes from {@code AuthService.login} so no HTTP
 * budget is spent obtaining it; each refresh response rotates the cookie and
 * the newest one is used for the next attempt.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place (besides AuthRateLimitTest) that exercises it, so re-enable it
    // with the production policy for both surfaces.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.attempts-per-minute=5",
    "app.auth.rate-limit.refresh-attempts-per-minute=30",
    // Per-account lockout stays off in this class (see AuthRateLimitTest)
    // so repeated failed logins cannot trip it and mask the rate-limit
    // assertions.
    "app.auth.login-lockout.enabled=false"
})
class AuthRefreshRateLimitTest {

  @TestConfiguration
  static class ControllableClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  private static final AtomicInteger IP_SEQ = new AtomicInteger(100);

  private static final String PASSWORD = "s3cret-pass";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private AuthService auth;

  @Autowired
  private MutableClock clock;

  private String ip;

  @BeforeEach
  void setUp() {
    ip = "10.201.0." + IP_SEQ.getAndIncrement();
    users.save(new User("refreshee", "refreshee@example.com", passwords.encode(PASSWORD)));
  }

  private RequestPostProcessor fromIp(String clientIp) {
    return request -> {
      request.setRemoteAddr(clientIp);
      return request;
    };
  }

  /** One service-level login, outside the HTTP budget. */
  private Cookie freshRefreshCookie() {
    String token = ((AuthService.LoginResult.Pair) auth.login("refreshee", PASSWORD)).result().pair().refreshToken();
    return new Cookie("refresh_token", token);
  }

  /** One refresh attempt with the current cookie; returns the response for chaining. */
  private MvcResult refreshAttempt(String clientIp, Cookie cookie) throws Exception {
    return mockMvc.perform(post("/api/auth/refresh")
            .with(fromIp(clientIp))
            .cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andReturn();
  }

  private static Cookie rotatedCookie(MvcResult result) {
    return result.getResponse().getCookie("refresh_token");
  }

  @Test
  void refreshBurstUnderTheLimitPasses() throws Exception {
    Cookie cookie = freshRefreshCookie();
    for (int i = 0; i < 30; i++) {
      cookie = rotatedCookie(refreshAttempt(ip, cookie));
    }
  }

  @Test
  void thirtyFirstRefreshIsRejectedWith429Envelope() throws Exception {
    Cookie cookie = freshRefreshCookie();
    for (int i = 0; i < 30; i++) {
      cookie = rotatedCookie(refreshAttempt(ip, cookie));
    }
    // The bucket is empty; the newest cookie is never presented to the
    // security chain — the filter rejects first.
    mockMvc.perform(post("/api/auth/refresh")
            .with(fromIp(ip))
            .cookie(cookie))
        .andExpect(status().isTooManyRequests())
        // 30/min = one token per 2 s.
        .andExpect(header().string("Retry-After", "2"))
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  void loginBucketIsNotConsumedByRefreshAttempts() throws Exception {
    Cookie cookie = freshRefreshCookie();
    for (int i = 0; i < 30; i++) {
      cookie = rotatedCookie(refreshAttempt(ip, cookie));
    }
    // The refresh bucket is empty, but the credential bucket is untouched:
    // a login attempt from the same IP is NOT throttled (401, not 429).
    mockMvc.perform(post("/api/auth/login")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"refreshee\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void refreshBucketIsNotConsumedByLoginAttempts() throws Exception {
    // Exhaust the credential bucket (5 failed logins).
    for (int i = 0; i < 5; i++) {
      mockMvc.perform(post("/api/auth/login")
              .with(fromIp(ip))
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"usernameOrEmail\":\"refreshee\",\"password\":\"wrong-password\"}"))
          .andExpect(status().isUnauthorized());
    }
    // The credential bucket is empty, but refresh has its own bucket.
    refreshAttempt(ip, freshRefreshCookie());
  }

  @Test
  void refreshBucketRefillsWithTheControllableClock() throws Exception {
    Cookie cookie = freshRefreshCookie();
    for (int i = 0; i < 30; i++) {
      cookie = rotatedCookie(refreshAttempt(ip, cookie));
    }
    mockMvc.perform(post("/api/auth/refresh")
            .with(fromIp(ip))
            .cookie(cookie))
        .andExpect(status().isTooManyRequests());

    clock.advance(Duration.ofSeconds(2));
    // One token refilled (30/min → 2 s per token): exactly one more
    // refresh passes …
    cookie = rotatedCookie(refreshAttempt(ip, cookie));
    // … and the bucket is empty again.
    mockMvc.perform(post("/api/auth/refresh")
            .with(fromIp(ip))
            .cookie(cookie))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "2"));

    clock.advance(Duration.ofMinutes(1));
    // Full refill: a fresh burst of 30 passes.
    for (int i = 0; i < 30; i++) {
      cookie = rotatedCookie(refreshAttempt(ip, cookie));
    }
  }
}
