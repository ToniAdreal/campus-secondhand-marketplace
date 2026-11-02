package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * Rate-limit behavior of the email-change REQUEST endpoint (backlog
 * #294) through the {@link AuthRateLimitFilter}: {@code POST
 * /api/auth/email-change} has its OWN per-IP bucket (5/minute here,
 * the production default), sized like the email-verification bucket
 * but deliberately separate from it — the request endpoint is a
 * mail-bombing surface that previously rode unthrottled while only
 * its confirm sibling shared the verification bucket. Each test uses
 * a fresh client IP because the limiter buckets are in-memory and
 * survive across tests within this class; every call in a test (the
 * register/verify/login setup included) carries that IP, so the
 * credential and verification buckets the setup touches stay on the
 * same IP without ever being the bucket under test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.attempts-per-minute=5",
    "app.auth.rate-limit.email-change-request-attempts-per-minute=5",
    // The gate is explicitly ON (the shared test profile turns it off)
    // so the setup can register, confirm via the mailbox token and log
    // in exactly like EmailChangeStepUpTest does.
    "app.auth.email-verification.enabled=true",
    // Keep the per-account lockout out of this class (it is not what is
    // being asserted, and the flows below never fail a login anyway).
    "app.auth.login-lockout.enabled=false"
})
class EmailChangeRequestRateLimitTest {

  /** Captures the raw verification token per user id — the registration mailbox. */
  static final Map<Long, String> MAILBOX = new ConcurrentHashMap<>();

  @TestConfiguration
  static class TestBeans {
    @Bean
    @Primary
    EmailVerificationMailSender capturingSender() {
      return (user, rawToken) -> MAILBOX.put(user.getId(), rawToken);
    }

    @Bean
    @Primary
    EmailChangeMailSender capturingChangeSender() {
      return (user, pendingEmail, rawToken) -> {
        // The mailed token itself is not asserted here; the sender only
        // needs to exist so the request path never touches a real mail
        // seam. The logging default would do too — this keeps the
        // class hermetic like EmailChangeStepUpTest.
      };
    }
  }

  private static final AtomicInteger IP_SEQ = new AtomicInteger(500);

  private static final String PASSWORD = "s3cret-pass1";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  private String ip;

  @BeforeEach
  void setUp() {
    ip = "10.205.0." + IP_SEQ.getAndIncrement();
    MAILBOX.clear();
  }

  private RequestPostProcessor fromIp(String clientIp) {
    return request -> {
      request.setRemoteAddr(clientIp);
      return request;
    };
  }

  /** Registers (gate on → 201, no session), confirms via the mailbox token, logs in. */
  private VerifiedSession registerVerifyAndLogin(String username, String email) throws Exception {
    MvcResult registered = mockMvc.perform(post("/api/auth/register")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"email\":\"" + email
                + "\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    long userId = ((Number) JsonPath.read(
        registered.getResponse().getContentAsString(), "$.data.user.id")).longValue();
    String token = MAILBOX.get(userId);
    assertThat(token).isNotBlank();
    mockMvc.perform(post("/api/auth/email-verification/confirm")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\"}"))
        .andExpect(status().isOk());
    MvcResult loggedIn = mockMvc.perform(post("/api/auth/login")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String access = JsonPath.read(
        loggedIn.getResponse().getContentAsString(), "$.data.accessToken");
    return new VerifiedSession(userId, access);
  }

  private record VerifiedSession(long userId, String accessToken) {}

  private void requestEmailChange(String accessToken, String newEmail, int expectedStatus)
      throws Exception {
    mockMvc.perform(post("/api/auth/email-change")
            .with(fromIp(ip))
            .header("Authorization", "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\","
                + "\"newEmail\":\"" + newEmail + "\"}"))
        .andExpect(status().is(expectedStatus));
  }

  @Test
  void sixthRequestIsRejectedWith429EnvelopeAndStagesNothing() throws Exception {
    VerifiedSession session = registerVerifyAndLogin("frank", "frank@example.com");
    // Five legitimate requests, each staging a different new address —
    // the token's step-up proof is fresh throughout (no clock moves).
    for (int i = 1; i <= 5; i++) {
      requestEmailChange(session.accessToken(), "frank.new" + i + "@example.com", 200);
    }
    assertThat(users.findById(session.userId()).orElseThrow().getPendingEmail())
        .isEqualTo("frank.new5@example.com");

    // The bucket is empty: the sixth request is throttled before the
    // controller ever sees it, so it stages nothing.
    mockMvc.perform(post("/api/auth/email-change")
            .with(fromIp(ip))
            .header("Authorization", "Bearer " + session.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\","
                + "\"newEmail\":\"frank.new6@example.com\"}"))
        .andExpect(status().isTooManyRequests())
        // No exact value: the five requests each pay a BCrypt password
        // check, so the bucket refills fractionally while the burst
        // runs and the wait for the next token lands below the nominal
        // 12 s — the PasswordChangeRateLimitTest presence pattern.
        .andExpect(header -> {
          String retryAfter = header.getResponse().getHeader("Retry-After");
          if (retryAfter == null) {
            throw new AssertionError("missing Retry-After header on the 429");
          }
        })
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"))
        .andExpect(jsonPath("$.data").doesNotExist());
    assertThat(users.findById(session.userId()).orElseThrow().getPendingEmail())
        .isEqualTo("frank.new5@example.com");
  }

  @Test
  void singleLegitimateRequestPasses() throws Exception {
    VerifiedSession session = registerVerifyAndLogin("grace", "grace@example.com");
    requestEmailChange(session.accessToken(), "grace.new@example.com", 200);
    assertThat(users.findById(session.userId()).orElseThrow().getPendingEmail())
        .isEqualTo("grace.new@example.com");
  }

  @Test
  void requestBucketDoesNotConsumeTheVerificationBucket() throws Exception {
    VerifiedSession session = registerVerifyAndLogin("heidi", "heidi@example.com");
    for (int i = 1; i <= 5; i++) {
      requestEmailChange(session.accessToken(), "heidi.new" + i + "@example.com", 200);
    }
    // The request bucket is empty, but the verification bucket is its
    // own: a confirm attempt from the same IP reaches the controller
    // and answers the token failure (401 for the bogus token), not 429.
    mockMvc.perform(post("/api/auth/email-change/confirm")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"bogus\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    // And the credential bucket is untouched too: the same user can
    // still log in from this IP.
    mockMvc.perform(post("/api/auth/login")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"heidi@example.com\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk());
  }
}
