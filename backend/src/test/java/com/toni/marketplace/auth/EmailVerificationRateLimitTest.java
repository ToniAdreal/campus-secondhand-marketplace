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
 * Rate-limit behavior of the email-verification endpoints (backlog #138)
 * through the {@link AuthRateLimitFilter}: resend and confirm share one
 * per-IP bucket (5/minute here, the production default), sized like the
 * credential bucket and separate from it — the backlog #101 pattern. Each
 * test uses a fresh client IP because the limiter buckets are in-memory
 * and survive across tests within this class.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.attempts-per-minute=5",
    "app.auth.rate-limit.email-verification-attempts-per-minute=5",
    // Keep the per-account lockout out of this class (it is not what is
    // being asserted, and the flows below never log in anyway).
    "app.auth.login-lockout.enabled=false"
})
class EmailVerificationRateLimitTest {

  private static final AtomicInteger IP_SEQ = new AtomicInteger(400);

  @Autowired
  private MockMvc mockMvc;

  private String ip;

  @BeforeEach
  void setUp() {
    ip = "10.204.0." + IP_SEQ.getAndIncrement();
  }

  private RequestPostProcessor fromIp(String clientIp) {
    return request -> {
      request.setRemoteAddr(clientIp);
      return request;
    };
  }

  private void resend(String clientIp) throws Exception {
    mockMvc.perform(post("/api/auth/email-verification/resend")
            .with(fromIp(clientIp))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"whoever@example.com\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void sixthResendIsRejectedWith429Envelope() throws Exception {
    for (int i = 0; i < 5; i++) {
      resend(ip);
    }
    mockMvc.perform(post("/api/auth/email-verification/resend")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"whoever@example.com\"}"))
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
      resend(ip);
    }
    // The bucket is empty: a confirm attempt from the same IP is
    // throttled before its (bogus) token is ever looked at.
    mockMvc.perform(post("/api/auth/email-verification/confirm")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"bogus\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }

  @Test
  void verificationBucketDoesNotConsumeTheCredentialBucket() throws Exception {
    for (int i = 0; i < 5; i++) {
      resend(ip);
    }
    // A login from the same IP is NOT throttled (401 for the unknown
    // user, not 429): the verification bucket is its own.
    mockMvc.perform(post("/api/auth/login")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"whoever\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }
}
