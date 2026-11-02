package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
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
 * Rate-limit behavior of the passkey management writes through
 * {@code AuthRateLimitFilter} (backlog #302): {@code DELETE
 * /api/auth/2fa/passkeys/{id}} and {@code PATCH
 * /api/auth/2fa/passkeys/{id}} share one per-<em>user</em> bucket
 * (production default 20/minute, like the #282 bucket — pinned to
 * 3/minute here so the burst arithmetic stays small), keyed on the
 * JWT principal id like the step-up/message buckets, with the per-IP
 * fallback for a missing/invalid token. The bucket never resets on
 * success — a completed delete or rename must not refill the
 * allowance — and it is deliberately separate from the passkey
 * bucket (backlog #295), which keeps challenge issuance and
 * enrolment verification, and from the TOTP lifecycle bucket
 * (backlog #301).
 *
 * <p>Buckets are in-memory and survive across tests within this class,
 * so each test uses a fresh user (per-user keys), mirroring
 * {@code PasskeyChallengeRateLimitTest}. Credential rows are seeded
 * repository-direct: delete and rename never touch verification
 * material (the public key, the counter, the AAGUID), so a seeded row
 * exercises exactly the controller/service path the throttle guards;
 * ceremony coverage of real enrolments lives in
 * {@code PasskeyCeremonyTest}. The Bearer token comes from
 * {@code JwtTokenService.createTokenPair}, as in
 * {@code SessionRevocationRateLimitTest}: its auth-time is now, so it
 * doubles as a fresh step-up proof (backlog #218) and the delete/rename
 * calls reach the service instead of stopping at the step-up gate.
 * A service login would NOT do here — once the row is seeded the
 * account holds a second factor, so login answers a 202 challenge
 * instead of a token pair.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the passkey management surfaces, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.passkey-management-attempts-per-minute=3",
    // Pin the neighbouring challenge bucket to the same small size so the
    // independence tests can exhaust it without 5 options calls.
    "app.auth.rate-limit.passkey-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the bucket
    // assertions on the bogus deletes below.
    "app.auth.login-lockout.enabled=false"})
class PasskeyManagementRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;
  @Autowired private PasskeyCredentialRepository passkeyCredentials;

  private final ObjectMapper json = new ObjectMapper();

  private User freshUser() {
    String tag = "passkeymgmt-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  /** Fresh-proof Bearer token: createTokenPair mints an access token whose auth-time is now. */
  private String bearer(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private static RequestPostProcessor fromIp(String clientIp) {
    return request -> {
      request.setRemoteAddr(clientIp);
      return request;
    };
  }

  private PasskeyCredential seedPasskey(User user) {
    return passkeyCredentials.save(new PasskeyCredential(user.getId(),
        "pk-" + UUID.randomUUID(), new byte[] {1}, 0L, new byte[16], "Old name"));
  }

  private void deleteOnce(String token, long passkeyId, int expectedStatus) throws Exception {
    mockMvc.perform(delete("/api/auth/2fa/passkeys/" + passkeyId)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().is(expectedStatus));
  }

  private void renameOnce(String token, long passkeyId, int expectedStatus) throws Exception {
    mockMvc.perform(patch("/api/auth/2fa/passkeys/" + passkeyId)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Renamed\"}"))
        .andExpect(status().is(expectedStatus));
  }

  private void registrationOptionsOnce(String token) throws Exception {
    mockMvc.perform(post("/api/auth/2fa/passkeys/registration/options")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }

  @Test
  void singleLegitimateDeletePasses() throws Exception {
    User user = freshUser();
    PasskeyCredential row = seedPasskey(user);
    assertThat(passkeyCredentials.countByUserId(user.getId())).isEqualTo(1);
    deleteOnce(bearer(user), row.getId(), 200);
    assertThat(passkeyCredentials.countByUserId(user.getId())).isZero();
  }

  @Test
  void singleLegitimateRenamePasses() throws Exception {
    User user = freshUser();
    PasskeyCredential row = seedPasskey(user);
    MvcResult result = mockMvc.perform(patch("/api/auth/2fa/passkeys/" + row.getId())
            .header("Authorization", "Bearer " + bearer(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Work laptop\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("Work laptop"))
        .andReturn();
    assertThat(json.readTree(result.getResponse().getContentAsString())
        .path("data").path("name").asText()).isEqualTo("Work laptop");
    assertThat(passkeyCredentials.findById(row.getId()).orElseThrow().getName())
        .isEqualTo("Work laptop");
  }

  @Test
  void deleteBurstIs429WithEnvelope() throws Exception {
    String token = bearer(freshUser());
    // Three bogus deletes consume the bucket (404: no passkey deleted).
    for (int i = 0; i < 3; i++) {
      deleteOnce(token, 987654321L, 404);
    }
    // The bucket is empty: the fourth delete is throttled before the
    // controller ever sees it.
    mockMvc.perform(delete("/api/auth/2fa/passkeys/987654321")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"))
        .andExpect(header -> {
          String retryAfter = header.getResponse().getHeader("Retry-After");
          if (retryAfter == null) {
            throw new AssertionError("missing Retry-After header on the 429");
          }
        });
  }

  @Test
  void managementBucketIsSharedAcrossDeleteAndRename() throws Exception {
    // Exhausting the bucket with deletes leaves a rename throttled too —
    // one bucket for the whole management surface, not one per endpoint.
    // The rename body is well-formed: the filter short-circuits before
    // the controller ever sees it.
    String token = bearer(freshUser());
    for (int i = 0; i < 3; i++) {
      deleteOnce(token, 987654321L, 404);
    }
    mockMvc.perform(patch("/api/auth/2fa/passkeys/987654321")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Renamed\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }

  @Test
  void managementBucketIsPerUser() throws Exception {
    User first = freshUser();
    String firstToken = bearer(first);
    for (int i = 0; i < 3; i++) {
      deleteOnce(firstToken, 987654321L, 404);
    }
    deleteOnce(firstToken, 987654321L, 429);

    deleteOnce(bearer(freshUser()), 987654321L, 404);
  }

  @Test
  void anonymousDeleteFallbackStillThrottles() throws Exception {
    // No token: the filter falls back to a per-IP bucket. The first three
    // anonymous deletes are answered 401 by the security chain (the
    // bucket still consumed each attempt); the fourth is short-circuited
    // 429 by the filter itself. A dedicated remote address keeps this
    // test's IP bucket away from every other test's.
    String ip = "203.0.113.72";
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(delete("/api/auth/2fa/passkeys/987654321")
              .with(fromIp(ip)))
          .andExpect(status().isUnauthorized());
    }
    mockMvc.perform(delete("/api/auth/2fa/passkeys/987654321")
            .with(fromIp(ip)))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"));
  }

  @Test
  void managementBucketExhaustionLeavesPasskeyChallengeBucketIntact() throws Exception {
    String token = bearer(freshUser());
    for (int i = 0; i < 3; i++) {
      deleteOnce(token, 987654321L, 404);
    }
    deleteOnce(token, 987654321L, 429);
    // The challenge bucket is untouched: the same user's registration
    // options call passes (its fresh-proof token is still in window).
    registrationOptionsOnce(token);
  }

  @Test
  void passkeyChallengeBucketExhaustionLeavesManagementBucketIntact() throws Exception {
    String token = bearer(freshUser());
    for (int i = 0; i < 3; i++) {
      registrationOptionsOnce(token);
    }
    mockMvc.perform(post("/api/auth/2fa/passkeys/registration/options")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isTooManyRequests());
    // The management bucket is untouched: a bogus delete reaches the
    // controller and answers 404, not 429.
    deleteOnce(token, 987654321L, 404);
  }
}
