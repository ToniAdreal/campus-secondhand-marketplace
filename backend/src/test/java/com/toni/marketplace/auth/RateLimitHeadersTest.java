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
 * Draft-ietf {@code RateLimit-*} headers on the 429 envelope (backlog #236):
 * every throttled endpoint's refusal carries {@code RateLimit-Limit} (the
 * refusing bucket's quota), {@code RateLimit-Remaining} (whole tokens left
 * — 0 on a denial, since a denied request consumes nothing) and
 * {@code RateLimit-Reset} (seconds until that bucket is full again),
 * alongside the existing {@code Retry-After} (seconds until the next
 * single token). Per-bucket values are pinned on two different buckets
 * (credential 5/min vs refresh 30/min); the shared writer in
 * {@link AuthRateLimitFilter} is what carries them to every other bucket.
 * Unthrottled endpoints carry none of the headers.
 *
 * <p>Same harness as {@link AuthRateLimitTest}: a {@link MutableClock}
 * makes refill deterministic, and each test uses a fresh client IP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates
@TestPropertySource(properties = {
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.attempts-per-minute=5",
    "app.auth.rate-limit.refresh-attempts-per-minute=30",
    "app.auth.login-lockout.enabled=false"
})
class RateLimitHeadersTest {

  @TestConfiguration
  static class ControllableClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  private static final AtomicInteger IP_SEQ = new AtomicInteger(1);

  private static final String PASSWORD = "s3cret-pass1";

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
    ip = "10.202.0." + IP_SEQ.getAndIncrement();
    users.save(new User("rlheaders", "rlheaders@example.com", passwords.encode(PASSWORD)));
  }

  private RequestPostProcessor fromIp(String clientIp) {
    return request -> {
      request.setRemoteAddr(clientIp);
      return request;
    };
  }

  private void failedLogin(String clientIp) throws Exception {
    mockMvc.perform(post("/api/auth/login")
            .with(fromIp(clientIp))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"rlheaders\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void credentialBucket429CarriesRateLimitHeaders() throws Exception {
    for (int i = 0; i < 5; i++) {
      failedLogin(ip);
    }
    mockMvc.perform(post("/api/auth/login")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"rlheaders\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(header().string("Retry-After", "12"))
        .andExpect(header().string("RateLimit-Limit", "5"))
        .andExpect(header().string("RateLimit-Remaining", "0"))
        // 5 tokens at 5/min: a full refill takes exactly 60 s.
        .andExpect(header().string("RateLimit-Reset", "60"));
  }

  @Test
  void resetHeaderCountsDownWhileRetryAfterTracksNextToken() throws Exception {
    for (int i = 0; i < 5; i++) {
      failedLogin(ip);
    }
    clock.advance(Duration.ofSeconds(6)); // half a token refilled: still denied
    mockMvc.perform(post("/api/auth/login")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"rlheaders\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "6"))
        .andExpect(header().string("RateLimit-Remaining", "0"))
        // 4.5 tokens still missing at 5/min: 54 s to a full bucket.
        .andExpect(header().string("RateLimit-Reset", "54"));
  }

  @Test
  void refreshBucket429CarriesItsOwnPerBucketValues() throws Exception {
    // Refresh attempts without a cookie are refused downstream (401), but
    // each one still consumes a token from the refresh bucket first.
    for (int i = 0; i < 30; i++) {
      mockMvc.perform(post("/api/auth/refresh")
              .with(fromIp(ip)))
          .andExpect(status().isUnauthorized());
    }
    mockMvc.perform(post("/api/auth/refresh")
            .with(fromIp(ip)))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429))
        // 30/min bucket: next token in 2 s, full refill in 60 s.
        .andExpect(header().string("Retry-After", "2"))
        .andExpect(header().string("RateLimit-Limit", "30"))
        .andExpect(header().string("RateLimit-Remaining", "0"))
        .andExpect(header().string("RateLimit-Reset", "60"));
  }

  @Test
  void unthrottledEndpointsCarryNoRateLimitHeaders() throws Exception {
    for (int i = 0; i < 10; i++) {
      mockMvc.perform(get("/api/items")
              .with(fromIp(ip)))
          .andExpect(status().isUnauthorized())
          .andExpect(header().doesNotExist("RateLimit-Limit"))
          .andExpect(header().doesNotExist("RateLimit-Remaining"))
          .andExpect(header().doesNotExist("RateLimit-Reset"))
          .andExpect(header().doesNotExist("Retry-After"));
    }
  }
}
