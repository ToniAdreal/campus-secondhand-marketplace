package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Per-IP rate limit of the second-factor exchange
 * {@code POST /api/auth/2fa/authenticate} (backlog #101) through the
 * {@link AuthRateLimitFilter}: the endpoint has its own bucket
 * (5/minute here, the production default), separate from the credential
 * bucket. The per-account TOTP lockout is disabled in this class — it
 * would answer 423 on the repeated failures below and mask the 401/429
 * assertions (it has its own test class, {@code TotpAuthenticateLockoutTest}).
 * Each test uses a fresh client IP because the limiter buckets are
 * in-memory and survive across tests within this class.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates
@TestPropertySource(properties = {
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.attempts-per-minute=5",
    "app.auth.rate-limit.totp-attempts-per-minute=5",
    "app.auth.totp-lockout.enabled=false",
    "app.auth.login-lockout.enabled=false"
})
class TotpAuthenticateRateLimitTest {

  private static final AtomicInteger IP_SEQ = new AtomicInteger(1);
  private static final String PASSWORD = "s3cret-pass1";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private TotpService totp;

  private String ip;
  private String secret;
  private String challenge;

  @BeforeEach
  void setUp() throws Exception {
    ip = "10.210.0." + IP_SEQ.getAndIncrement();
    String username = "totprl" + IP_SEQ.get();
    MvcResult registered = mockMvc.perform(post("/api/auth/register")
            .with(fromIp("10.211.0." + IP_SEQ.get()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                + "@example.com\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String access = JsonPath.read(registered.getResponse().getContentAsString(),
        "$.data.accessToken");
    MvcResult setup = mockMvc.perform(post("/api/auth/2fa/setup")
            .header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andReturn();
    secret = JsonPath.read(setup.getResponse().getContentAsString(), "$.data.secret");
    mockMvc.perform(post("/api/auth/2fa/enable")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + totp.codeAt(secret, Instant.now()) + "\"}"))
        .andExpect(status().isOk());
    challenge = loginChallenge(username, "10.212.0." + IP_SEQ.get());
  }

  private static RequestPostProcessor fromIp(String clientIp) {
    return request -> {
      request.setRemoteAddr(clientIp);
      return request;
    };
  }

  private String loginChallenge(String username, String clientIp) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/login")
            .with(fromIp(clientIp))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + username
                + "\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isAccepted())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.challenge");
  }

  private String wrongCode() {
    Set<String> valid = Set.of(
        totp.codeAt(secret, Instant.now().minusSeconds(30)),
        totp.codeAt(secret, Instant.now()),
        totp.codeAt(secret, Instant.now().plusSeconds(30)));
    for (String candidate : List.of("000000", "111111", "222222", "333333", "444444")) {
      if (!valid.contains(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("no wrong code candidate");
  }

  private void failedExchange(String clientIp) throws Exception {
    mockMvc.perform(post("/api/auth/2fa/authenticate")
            .with(fromIp(clientIp))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"challenge\":\"" + challenge + "\",\"code\":\"" + wrongCode() + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void burstUnderTheLimitPasses() throws Exception {
    for (int i = 0; i < 5; i++) {
      failedExchange(ip);
    }
  }

  @Test
  void sixthExchangeInAMinuteIsRejectedWith429Envelope() throws Exception {
    for (int i = 0; i < 5; i++) {
      failedExchange(ip);
    }
    mockMvc.perform(post("/api/auth/2fa/authenticate")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"challenge\":\"" + challenge + "\",\"code\":\"" + wrongCode() + "\"}"))
        .andExpect(status().isTooManyRequests())
        // 5/min = one token per 12 s.
        .andExpect(header().string("Retry-After", "12"))
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  void totpBucketDoesNotStarveTheCredentialBucket() throws Exception {
    for (int i = 0; i < 5; i++) {
      failedExchange(ip);
    }
    mockMvc.perform(post("/api/auth/2fa/authenticate")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"challenge\":\"" + challenge + "\",\"code\":\"" + wrongCode() + "\"}"))
        .andExpect(status().isTooManyRequests());
    // The credential bucket is a different bucket: a password login from
    // the very same IP still goes through and issues a fresh challenge.
    MvcResult result = mockMvc.perform(post("/api/auth/login")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"totprl" + IP_SEQ.get()
                + "\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isUnauthorized())
        .andReturn();
    // (401 wrong-password proves the request reached AuthService; the
    // username lookup is irrelevant — what matters is: not 429.)
    org.assertj.core.api.Assertions.assertThat(
        result.getResponse().getContentAsString()).contains("\"code\":401");
  }
}
