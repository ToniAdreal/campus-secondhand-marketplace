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
 * Rate-limit behavior of the password-reset endpoints (backlog #90)
 * through the {@link AuthRateLimitFilter}: request and confirm share one
 * per-IP bucket (5/minute here, the production default), sized like the
 * credential bucket. Each test uses a fresh client IP because the limiter
 * buckets are in-memory and survive across tests within this class.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.attempts-per-minute=5",
    "app.auth.rate-limit.password-reset-attempts-per-minute=5",
    // Keep the per-account lockout out of this class (it is not what is
    // being asserted, and the flows below never log in anyway).
    "app.auth.login-lockout.enabled=false"
})
class PasswordResetRateLimitTest {

  private static final AtomicInteger IP_SEQ = new AtomicInteger(300);

  @Autowired
  private MockMvc mockMvc;

  private String ip;

  @BeforeEach
  void setUp() {
    ip = "10.203.0." + IP_SEQ.getAndIncrement();
  }

  private RequestPostProcessor fromIp(String clientIp) {
    return request -> {
      request.setRemoteAddr(clientIp);
      return request;
    };
  }

  private void requestReset(String clientIp) throws Exception {
    mockMvc.perform(post("/api/auth/password-reset")
            .with(fromIp(clientIp))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"whoever\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void sixthResetRequestIsRejectedWith429Envelope() throws Exception {
    for (int i = 0; i < 5; i++) {
      requestReset(ip);
    }
    mockMvc.perform(post("/api/auth/password-reset")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"whoever\"}"))
        .andExpect(status().isTooManyRequests())
        // 5/min = one token per 12 s.
        .andExpect(header().string("Retry-After", "12"))
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  void confirmSharesTheSameBucket() throws Exception {
    for (int i = 0; i < 5; i++) {
      requestReset(ip);
    }
    // The bucket is empty: a confirm attempt from the same IP is
    // throttled before its (bogus) token is ever looked at.
    mockMvc.perform(post("/api/auth/password-reset/confirm")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"bogus\",\"newPassword\":\"n3w-s3cret12\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }

  @Test
  void resetBucketDoesNotConsumeTheCredentialBucket() throws Exception {
    for (int i = 0; i < 5; i++) {
      requestReset(ip);
    }
    // A login from the same IP is NOT throttled (401 for the unknown
    // user, not 429): the reset bucket is its own.
    mockMvc.perform(post("/api/auth/login")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"whoever\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }
}
