package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-to-end rate-limit behavior of {@code POST /api/auth/login} and
 * {@code POST /api/auth/register} through the {@link AuthRateLimitFilter}.
 *
 * <p>The application {@code Clock} bean is replaced with a {@link MutableClock}
 * so refill timing is deterministic. Each test uses a fresh client IP (the
 * limiter buckets are in-memory and survive across tests within this class),
 * and the fixture user is created directly via the repository so the HTTP
 * bucket starts untouched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises it, so re-enable with the production policy.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.attempts-per-minute=5"
})
class AuthRateLimitTest {

  @TestConfiguration
  static class ControllableClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  private static final AtomicInteger IP_SEQ = new AtomicInteger(1);

  private static final String PASSWORD = "s3cret-pass";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private MutableClock clock;

  private String ip;

  @BeforeEach
  void setUp() {
    ip = "10.200.0." + IP_SEQ.getAndIncrement();
    users.save(new User("victim", "victim@example.com", passwords.encode(PASSWORD)));
  }

  private RequestPostProcessor fromIp(String clientIp) {
    return request -> {
      request.setRemoteAddr(clientIp);
      return request;
    };
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

  private void registerAttempt(String clientIp, int n) throws Exception {
    mockMvc.perform(post("/api/auth/register")
            .with(fromIp(clientIp))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"rluser" + n + "\",\"email\":\"rluser" + n
                + "@example.com\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }

  @Test
  void legitimateBurstUnderTheLimitPasses() throws Exception {
    for (int i = 0; i < 5; i++) {
      loginAttempt(ip, "victim", "wrong-password");
    }
  }

  @Test
  void sixthLoginAttemptInAMinuteIsRejectedWith429Envelope() throws Exception {
    for (int i = 0; i < 5; i++) {
      loginAttempt(ip, "victim", "wrong-password");
    }
    mockMvc.perform(post("/api/auth/login")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"victim\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "12"))
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  void registerAttemptsAreRateLimited() throws Exception {
    for (int i = 0; i < 5; i++) {
      registerAttempt(ip, i);
    }
    mockMvc.perform(post("/api/auth/register")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"rluser5\",\"email\":\"rluser5@example.com\","
                + "\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value(429));
  }

  @Test
  void loginAndRegisterShareOneBucketPerIp() throws Exception {
    // 3 failed logins + 2 registers = 5 tokens; the 6th attempt of either
    // kind is throttled. Documents the shared per-IP bucket decision.
    for (int i = 0; i < 3; i++) {
      loginAttempt(ip, "victim", "wrong-password");
    }
    registerAttempt(ip, 0);
    registerAttempt(ip, 1);
    mockMvc.perform(post("/api/auth/register")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"rluser9\",\"email\":\"rluser9@example.com\","
                + "\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }

  @Test
  void successfulLoginResetsTheBucket() throws Exception {
    for (int i = 0; i < 4; i++) {
      loginAttempt(ip, "victim", "wrong-password");
    }
    // The successful login resets the bucket …
    mockMvc.perform(post("/api/auth/login")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"victim\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    // … so a full fresh burst of 5 passes again …
    for (int i = 0; i < 5; i++) {
      loginAttempt(ip, "victim", "wrong-password");
    }
    // … and only the 6th post-reset attempt is throttled.
    mockMvc.perform(post("/api/auth/login")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"victim\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }

  @Test
  void failedLoginDoesNotResetTheBucket() throws Exception {
    for (int i = 0; i < 5; i++) {
      loginAttempt(ip, "victim", "wrong-password");
    }
    // A 401 right after 5 failures would reset nothing — still throttled.
    mockMvc.perform(post("/api/auth/login")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"victim\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void bucketRefillsAsTheControllableClockAdvances() throws Exception {
    for (int i = 0; i < 5; i++) {
      loginAttempt(ip, "victim", "wrong-password");
    }
    mockMvc.perform(post("/api/auth/login")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"victim\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "12"));

    clock.advance(Duration.ofSeconds(12));
    // One token refilled: exactly one more attempt passes …
    loginAttempt(ip, "victim", "wrong-password");
    // … and the bucket is empty again.
    mockMvc.perform(post("/api/auth/login")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"victim\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "12"));

    clock.advance(Duration.ofMinutes(1));
    // Full refill: a fresh burst of 5 passes.
    for (int i = 0; i < 5; i++) {
      loginAttempt(ip, "victim", "wrong-password");
    }
  }

  @Test
  void retryAfterHeaderCountsDown() throws Exception {
    for (int i = 0; i < 5; i++) {
      loginAttempt(ip, "victim", "wrong-password");
    }
    clock.advance(Duration.ofSeconds(6));
    mockMvc.perform(post("/api/auth/login")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"victim\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "6"));
  }

  @Test
  void rateLimitIsPerClientIp() throws Exception {
    String otherIp = "10.200.9.9";
    for (int i = 0; i < 5; i++) {
      loginAttempt(ip, "victim", "wrong-password");
    }
    mockMvc.perform(post("/api/auth/login")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"victim\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isTooManyRequests());

    // A different client IP has its own untouched bucket.
    loginAttempt(otherIp, "victim", "wrong-password");
  }

  @Test
  void otherEndpointsAreNotThrottled() throws Exception {
    // The filter only inspects POST /api/auth/login and /register. Hammering
    // an unrelated endpoint must never 429: GET /api/items without a token
    // answers 401 from the security entry point every time, proving the
    // throttle did not touch it.
    for (int i = 0; i < 10; i++) {
      mockMvc.perform(get("/api/items")
              .with(fromIp(ip)))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value(401));
    }
  }
}
