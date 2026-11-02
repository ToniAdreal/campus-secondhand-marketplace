package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
 * Rate-limit behavior of the TOTP lifecycle writes through
 * {@code AuthRateLimitFilter} (backlog #301): {@code POST
 * /api/auth/2fa/setup}, {@code POST /api/auth/2fa/enable}, {@code POST
 * /api/auth/2fa/disable} and {@code POST
 * /api/auth/2fa/recovery-codes/regenerate} share one per-<em>user</em>
 * bucket (production default 5/minute, like the step-up bucket — pinned
 * to 3/minute here so the burst arithmetic stays small), keyed on the
 * JWT principal id like the step-up/message buckets, with the per-IP
 * fallback for a missing/invalid token. The bucket never resets on
 * success — a completed enrolment or regeneration must not refill the
 * allowance — and it is deliberately separate from the TOTP
 * authenticate bucket (backlog #101), which keeps the
 * {@code /2fa/authenticate} exchange itself, and from the passkey
 * bucket (backlog #295), which keeps challenge issuance and enrolment
 * verification.
 *
 * <p>Buckets are in-memory and survive across tests within this class,
 * so each test uses a fresh user (per-user keys) and a fresh client IP
 * for every IP-keyed call, mirroring {@code PasskeyChallengeRateLimitTest}.
 * A legitimate enrolment is exactly three lifecycle writes at most
 * (setup + enable + disable, or setup + enable + regenerate), so the
 * pinned 3/minute ceiling never bites a single legitimate flow — the
 * burst tests pin that the fourth write, not the third, is refused.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the TOTP lifecycle surfaces, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.totp-setup-attempts-per-minute=3",
    // Pin the neighbouring buckets to the same small size so the
    // independence tests can exhaust them without 5 exchanges.
    "app.auth.rate-limit.totp-attempts-per-minute=3",
    "app.auth.rate-limit.passkey-attempts-per-minute=3",
    // The per-account TOTP lockout stays off so no other throttle masks
    // the bucket assertions on the garbage exchanges below.
    "app.auth.totp-lockout.enabled=false",
    "app.auth.login-lockout.enabled=false"})
class TotpSetupRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();
  private static final AtomicInteger IP_SEQ = new AtomicInteger();
  private static final String PASSWORD = "s3cret-pass1";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private TotpService totp;

  private final ObjectMapper json = new ObjectMapper();

  /** Distinct client IPs per call keep the per-IP login rate-limit bucket out of the way. */
  private static RequestPostProcessor uniqueIp() {
    return request -> {
      request.setRemoteAddr("10.93.0." + IP_SEQ.incrementAndGet());
      return request;
    };
  }

  private static RequestPostProcessor fromIp(String clientIp) {
    return request -> {
      request.setRemoteAddr(clientIp);
      return request;
    };
  }

  /** Registers a user; registration mints a token whose proof instant is now. */
  private String register(String username) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/register")
            .with(uniqueIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                + "@example.com\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
  }

  private String freshUsername() {
    return "totpsetup-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
  }

  private MvcResult postSetup(String bearer) throws Exception {
    return mockMvc.perform(post("/api/auth/2fa/setup")
            .header("Authorization", "Bearer " + bearer))
        .andReturn();
  }

  private void setupOnce(String bearer) throws Exception {
    MvcResult result = postSetup(bearer);
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
  }

  private String setupSecret(String bearer) throws Exception {
    MvcResult result = postSetup(bearer);
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    JsonNode data = json.readTree(result.getResponse().getContentAsString()).path("data");
    assertThat(data.path("secret").asText()).isNotBlank();
    assertThat(data.path("otpauthUri").asText()).contains("otpauth://");
    return data.path("secret").asText();
  }

  private List<String> enable(String bearer, String secret) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/2fa/enable")
            .header("Authorization", "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + totp.codeAt(secret, Instant.now()) + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    List<String> codes =
        JsonPath.read(result.getResponse().getContentAsString(), "$.data.recoveryCodes");
    assertThat(codes).hasSize(TotpService.RECOVERY_CODE_COUNT);
    return codes;
  }

  private void disable(String bearer, String secret) throws Exception {
    mockMvc.perform(post("/api/auth/2fa/disable")
            .header("Authorization", "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\",\"code\":\""
                + totp.codeAt(secret, Instant.now()) + "\"}"))
        .andExpect(status().isOk());
  }

  private void regenerate(String bearer, String secret) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/2fa/recovery-codes/regenerate")
            .header("Authorization", "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\",\"code\":\""
                + totp.codeAt(secret, Instant.now()) + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    List<String> codes =
        JsonPath.read(result.getResponse().getContentAsString(), "$.data.recoveryCodes");
    assertThat(codes).hasSize(TotpService.RECOVERY_CODE_COUNT);
  }

  @Test
  void singleLegitimateSetupPasses() throws Exception {
    setupSecret(register(freshUsername()));
  }

  @Test
  void singleLegitimateEnablePasses() throws Exception {
    String access = register(freshUsername());
    enable(access, setupSecret(access));
  }

  @Test
  void singleLegitimateDisablePasses() throws Exception {
    String access = register(freshUsername());
    String secret = setupSecret(access);
    enable(access, secret);
    disable(access, secret);
  }

  @Test
  void singleLegitimateRegeneratePasses() throws Exception {
    String access = register(freshUsername());
    String secret = setupSecret(access);
    enable(access, secret);
    regenerate(access, secret);
  }

  @Test
  void fourthSetupAttemptIsRejectedWith429Envelope() throws Exception {
    String access = register(freshUsername());
    setupOnce(access);
    setupOnce(access);
    setupOnce(access);
    MvcResult result = postSetup(access);
    assertThat(result.getResponse().getStatus()).isEqualTo(429);
    JsonNode body = json.readTree(result.getResponse().getContentAsString());
    assertThat(body.path("code").asInt()).isEqualTo(429);
    assertThat(body.path("message").asText()).isEqualTo("too many requests");
    assertThat(result.getResponse().getHeader("Retry-After")).isNotBlank();
  }

  @Test
  void totpSetupBucketIsSharedAcrossSetupEnableDisableAndRegenerate() throws Exception {
    // Exhausting the bucket with setups leaves an enable throttled too —
    // one bucket for the whole lifecycle surface, not one per endpoint.
    // The enable body is a well-formed dummy: the filter short-circuits
    // before the controller ever sees it.
    String access = register(freshUsername());
    setupOnce(access);
    setupOnce(access);
    setupOnce(access);
    mockMvc.perform(post("/api/auth/2fa/enable")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"123456\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }

  @Test
  void totpSetupBucketIsPerUser() throws Exception {
    String first = register(freshUsername());
    setupOnce(first);
    setupOnce(first);
    setupOnce(first);
    assertThat(postSetup(first).getResponse().getStatus()).isEqualTo(429);

    setupSecret(register(freshUsername()));
  }

  @Test
  void totpSetupBucketExhaustionLeavesTotpAuthenticateBucketIntact() throws Exception {
    String access = register(freshUsername());
    setupOnce(access);
    setupOnce(access);
    setupOnce(access);
    assertThat(postSetup(access).getResponse().getStatus()).isEqualTo(429);
    // The TOTP authenticate bucket is untouched: a garbage exchange from
    // a fresh IP reaches the service and answers 401, not 429.
    mockMvc.perform(post("/api/auth/2fa/authenticate")
            .with(fromIp("203.0.113.70"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"challenge\":\"garbage-challenge-token\",\"code\":\"123456\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void totpAuthenticateBucketExhaustionLeavesTotpSetupBucketIntact() throws Exception {
    String ip = "203.0.113.71";
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(post("/api/auth/2fa/authenticate")
              .with(fromIp(ip))
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"challenge\":\"garbage-challenge-token\",\"code\":\"123456\"}"))
          .andExpect(status().isUnauthorized());
    }
    mockMvc.perform(post("/api/auth/2fa/authenticate")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"challenge\":\"garbage-challenge-token\",\"code\":\"123456\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
    // The TOTP lifecycle bucket is untouched: a fresh user's setup passes.
    setupSecret(register(freshUsername()));
  }

  @Test
  void totpSetupBucketExhaustionLeavesPasskeyBucketIntact() throws Exception {
    String access = register(freshUsername());
    setupOnce(access);
    setupOnce(access);
    setupOnce(access);
    assertThat(postSetup(access).getResponse().getStatus()).isEqualTo(429);
    // The passkey bucket is untouched: the same user's registration
    // options call passes (its fresh-proof token is still in window).
    mockMvc.perform(post("/api/auth/2fa/passkeys/registration/options")
            .header("Authorization", "Bearer " + access))
        .andExpect(status().isOk());
  }

  @Test
  void passkeyBucketExhaustionLeavesTotpSetupBucketIntact() throws Exception {
    String access = register(freshUsername());
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(post("/api/auth/2fa/passkeys/registration/options")
              .header("Authorization", "Bearer " + access))
          .andExpect(status().isOk());
    }
    mockMvc.perform(post("/api/auth/2fa/passkeys/registration/options")
            .header("Authorization", "Bearer " + access))
        .andExpect(status().isTooManyRequests());
    // The TOTP lifecycle bucket is untouched: the same user's setup passes.
    setupSecret(access);
  }
}
