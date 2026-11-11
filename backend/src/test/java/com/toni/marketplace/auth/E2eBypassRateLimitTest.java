package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * The e2e bypass for the credential bucket (backlog #373) through the
 * {@link AuthRateLimitFilter}.
 *
 * <p>The thirteen Playwright flows share one backend and one client IP
 * (127.0.0.1), so their register/login bursts exhaust the production
 * 5/minute credential bucket mid-suite (main run 42734704718: register
 * expected 200, got 429) even though each flow on its own stays under
 * the ceiling. With {@code app.auth.rate-limit.e2e-bypass=true} the
 * credential limiter is built disabled: login and register are never
 * throttled (refresh rides along — the filter's should-not-filter gate
 * for all three reads the credential limiter's enabled flag), while
 * every other limiter keeps its own bucket.
 *
 * <p>Each test uses a fresh client IP (the limiter buckets are in-memory
 * and survive across tests within this class), and the fixture user is
 * created directly via the repository so the HTTP bucket starts
 * untouched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the bypass, so re-enable with the production
    // policy — plus the bypass under test.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.attempts-per-minute=5",
    "app.auth.rate-limit.e2e-bypass=true",
    // These tests hammer failed logins; the per-account lockout
    // (backlog #60) would trip on them and mask the 401 assertions,
    // so it stays off here.
    "app.auth.login-lockout.enabled=false"
})
class E2eBypassRateLimitTest {

  private static final AtomicInteger IP_SEQ = new AtomicInteger(1);

  private static final String PASSWORD = "s3cret-pass1";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  private String ip;

  @BeforeEach
  void setUp() {
    ip = "10.201.0." + IP_SEQ.getAndIncrement();
    users.save(new User("victim", "victim@example.com", passwords.encode(PASSWORD)));
  }

  private RequestPostProcessor fromIp(String clientIp) {
    return request -> {
      request.setRemoteAddr(clientIp);
      return request;
    };
  }

  private void registerAttempt(String clientIp, int n) throws Exception {
    mockMvc.perform(post("/api/auth/register")
            .with(fromIp(clientIp))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"bypassuser" + n + "\",\"email\":\"bypassuser" + n
                + "@example.com\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }

  private void loginAttempt(String clientIp, String usernameOrEmail, String password)
      throws Exception {
    mockMvc.perform(post("/api/auth/login")
            .with(fromIp(clientIp))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + usernameOrEmail
                + "\",\"password\":\"" + password + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  private void requestReset(String clientIp) throws Exception {
    mockMvc.perform(post("/api/auth/password-reset")
            .with(fromIp(clientIp))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"whoever-" + clientIp + "\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void registerBurstAboveTheProductionCeilingPasses() throws Exception {
    // Twice the production ceiling (5/minute) from one IP: with the
    // bypass on, every register lands instead of the 6th answering 429.
    for (int i = 0; i < 10; i++) {
      registerAttempt(ip, i);
    }
  }

  @Test
  void failedLoginBurstAboveTheProductionCeilingPasses() throws Exception {
    // Twice the production ceiling of failed logins from one IP: with
    // the bypass on, every attempt answers 401 — none is throttled.
    for (int i = 0; i < 10; i++) {
      loginAttempt(ip, "victim", "wrong-password");
    }
  }

  @Test
  void bypassDoesNotLiftOtherBuckets() throws Exception {
    // The bypass is scoped to the credential bucket: the password-reset
    // bucket (its own 5/minute per IP) still throttles the 6th request
    // from the same IP while the bypass is on.
    for (int i = 0; i < 5; i++) {
      requestReset(ip);
    }
    mockMvc.perform(post("/api/auth/password-reset")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"whoever-" + ip + "\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "12"))
        .andExpect(jsonPath("$.code").value(429));
  }
}
