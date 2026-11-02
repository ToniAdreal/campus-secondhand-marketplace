package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.AttestationConveyancePreference;
import com.webauthn4j.data.AuthenticatorSelectionCriteria;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialDescriptor;
import com.webauthn4j.data.PublicKeyCredentialRpEntity;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.PublicKeyCredentialUserEntity;
import com.webauthn4j.data.ResidentKeyRequirement;
import com.webauthn4j.data.UserVerificationRequirement;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.data.extension.client.AuthenticationExtensionsClientInputs;
import com.webauthn4j.data.extension.client.RegistrationExtensionClientInput;
import com.webauthn4j.test.EmulatorUtil;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Rate-limit behavior of the passkey challenge-issuance and
 * enrolment-verification shapes through {@code AuthRateLimitFilter}
 * (backlog #295): {@code POST
 * /api/auth/2fa/passkeys/registration/options}, {@code POST
 * /api/auth/2fa/passkeys/registration/verify} and {@code POST
 * /api/auth/2fa/passkeys/assertion/options} share one per-<em>user</em>
 * bucket (production default 5/minute, like the step-up bucket — pinned
 * to 3/minute here so the burst arithmetic stays small), keyed on the
 * JWT principal id like the step-up/message buckets, with the per-IP
 * fallback for a missing/invalid token. The bucket never resets on
 * success — a completed enrolment must not refill the allowance — and
 * it is deliberately separate from the TOTP authenticate bucket
 * (backlog #101), which keeps the {@code /2fa/authenticate} exchange
 * itself. Out of scope, and unthrottled here: the 2FA
 * setup/enable/disable endpoints and the passkey list/delete/rename
 * shapes.
 *
 * <p>Keying split, pinned by the last two tests: the assertion-options
 * shape is public by design (its caller is not authenticated yet), so
 * it lands on the per-IP fallback key, while the two registration
 * shapes land on the JWT-principal key — assertion traffic can
 * therefore never starve an authenticated user's registration bucket.
 *
 * <p>Buckets are in-memory and survive across tests within this class,
 * so each test uses a fresh user (per-user keys) and a fresh client IP
 * for every IP-keyed call, mirroring {@code TotpAuthenticateRateLimitTest}
 * and {@code PasswordChangeRateLimitTest}. The ceremony helpers mirror
 * {@code PasskeyCeremonyTest} (software authenticator + client platform
 * from webauthn4j-test playing the browser).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the passkey challenge surfaces, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.passkey-attempts-per-minute=3",
    // Pin the TOTP bucket to the same small size so the
    // independence tests can exhaust it without 5 exchanges.
    "app.auth.rate-limit.totp-attempts-per-minute=3",
    // The per-account TOTP lockout stays off so no other throttle masks
    // the bucket assertions on the garbage exchanges below.
    "app.auth.totp-lockout.enabled=false",
    "app.auth.login-lockout.enabled=false"})
class PasskeyChallengeRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordService passwords;
  @Autowired private AuthService auth;
  @Autowired private PasskeyCredentialRepository passkeyCredentials;

  private final ObjectMapper json = new ObjectMapper();
  private final ObjectConverter webauthnConverter = new ObjectConverter();

  private User freshUser() {
    String tag = "passkeyrl-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  /** Fresh-proof Bearer token via the service login, as in PasskeyCeremonyTest. */
  private String bearer(User user) {
    JwtTokenService.TokenPair pair =
        ((AuthService.LoginResult.Pair) auth.login(user.getUsername(), "password"))
            .result().pair();
    return pair.accessToken();
  }

  private static RequestPostProcessor fromIp(String clientIp) {
    return request -> {
      request.setRemoteAddr(clientIp);
      return request;
    };
  }

  /** A software client platform whose origin the server accepts. */
  private ClientPlatform clientPlatform() {
    return new ClientPlatform(new Origin("http://localhost:5173"),
        new WebAuthnAuthenticatorAdaptor(EmulatorUtil.NONE_ATTESTATION_AUTHENTICATOR,
            webauthnConverter));
  }

  private JsonNode dataOf(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString()).path("data");
  }

  /** Drives options → create → verify through MockMvc; returns the enrolled credential id. */
  private String enrollPasskey(String bearer) throws Exception {
    MvcResult options = mockMvc.perform(post("/api/auth/2fa/passkeys/registration/options")
            .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode data = dataOf(options);
    String credentialJson = webauthnConverter.getJsonConverter()
        .writeValueAsString(clientPlatform().create(creationOptions(data)));
    MvcResult verify = mockMvc.perform(post("/api/auth/2fa/passkeys/registration/verify")
            .header("Authorization", "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("credential", credentialJson))))
        .andExpect(status().isOk())
        .andReturn();
    String credentialId = dataOf(verify).path("credentialId").asText();
    assertThat(credentialId).isNotBlank();
    return credentialId;
  }

  private PublicKeyCredentialCreationOptions creationOptions(JsonNode data) {
    byte[] challengeBytes = PasskeyService.base64UrlDecode(data.path("challenge").asText());
    List<PublicKeyCredentialDescriptor> excludeCredentials =
        jsonList(data, "excludeCredentials").stream()
            .map(id -> new PublicKeyCredentialDescriptor(PublicKeyCredentialType.PUBLIC_KEY,
                PasskeyService.base64UrlDecode(id), Set.of()))
            .toList();
    return new PublicKeyCredentialCreationOptions(
        new PublicKeyCredentialRpEntity(data.path("rpId").asText(), data.path("rpName").asText()),
        new PublicKeyCredentialUserEntity(
            PasskeyService.base64UrlDecode(data.path("user").path("id").asText()),
            data.path("user").path("name").asText(),
            data.path("user").path("displayName").asText()),
        new DefaultChallenge(challengeBytes),
        PasskeyService.pubKeyCredParams(),
        data.path("timeout").asLong(),
        excludeCredentials,
        new AuthenticatorSelectionCriteria(null, ResidentKeyRequirement.DISCOURAGED,
            UserVerificationRequirement.PREFERRED),
        AttestationConveyancePreference.NONE,
        new AuthenticationExtensionsClientInputs<RegistrationExtensionClientInput>());
  }

  private List<String> jsonList(JsonNode data, String field) {
    return json.convertValue(data.path(field),
        json.getTypeFactory().constructCollectionType(List.class, String.class));
  }

  private void registrationOptionsOnce(String bearer) throws Exception {
    mockMvc.perform(post("/api/auth/2fa/passkeys/registration/options")
            .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk());
  }

  @Test
  void singleLegitimateEnrolmentPasses() throws Exception {
    User user = freshUser();
    String credentialId = enrollPasskey(bearer(user));
    assertThat(passkeyCredentials.countByUserId(user.getId())).isEqualTo(1);
    assertThat(passkeyCredentials.findByCredentialId(credentialId)).isPresent();
  }

  @Test
  void fourthRegistrationOptionsAttemptIsRejectedWith429Envelope() throws Exception {
    String token = bearer(freshUser());
    registrationOptionsOnce(token);
    registrationOptionsOnce(token);
    registrationOptionsOnce(token);
    mockMvc.perform(post("/api/auth/2fa/passkeys/registration/options")
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
  void registrationBucketIsSharedAcrossOptionsAndVerify() throws Exception {
    // Exhausting the bucket with options leaves a verify throttled too —
    // one bucket for the whole challenge/enrolment surface, not one per
    // endpoint. The verify body is deliberately a dummy: the filter
    // short-circuits before the controller ever sees it.
    String token = bearer(freshUser());
    registrationOptionsOnce(token);
    registrationOptionsOnce(token);
    registrationOptionsOnce(token);
    mockMvc.perform(post("/api/auth/2fa/passkeys/registration/verify")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\":\"not-a-credential\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }

  @Test
  void passkeyBucketIsPerUser() throws Exception {
    User first = freshUser();
    String firstToken = bearer(first);
    registrationOptionsOnce(firstToken);
    registrationOptionsOnce(firstToken);
    registrationOptionsOnce(firstToken);
    mockMvc.perform(post("/api/auth/2fa/passkeys/registration/options")
            .header("Authorization", "Bearer " + firstToken))
        .andExpect(status().isTooManyRequests());

    registrationOptionsOnce(bearer(freshUser()));
  }

  @Test
  void passkeyBucketExhaustionLeavesTotpBucketIntact() throws Exception {
    String token = bearer(freshUser());
    registrationOptionsOnce(token);
    registrationOptionsOnce(token);
    registrationOptionsOnce(token);
    mockMvc.perform(post("/api/auth/2fa/passkeys/registration/options")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isTooManyRequests());
    // The TOTP bucket is untouched: a garbage exchange from a fresh IP
    // reaches the service and answers 401, not 429.
    mockMvc.perform(post("/api/auth/2fa/authenticate")
            .with(fromIp("203.0.113.60"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"challenge\":\"garbage-challenge-token\",\"code\":\"123456\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void totpBucketExhaustionLeavesPasskeyBucketIntact() throws Exception {
    String ip = "203.0.113.61";
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
    // The passkey bucket is untouched: a fresh user's options call passes.
    registrationOptionsOnce(bearer(freshUser()));
  }

  @Test
  void anonymousAssertionOptionsFallbackStillThrottles() throws Exception {
    // No token: the filter falls back to a per-IP bucket. The first three
    // anonymous assertion-options calls are answered 401 by the service
    // (the bucket still consumed each attempt); the fourth is
    // short-circuited 429 by the filter itself. A dedicated remote
    // address keeps this test's IP bucket away from every other test's.
    String ip = "203.0.113.62";
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(post("/api/auth/2fa/passkeys/assertion/options")
              .with(fromIp(ip))
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"challenge\":\"garbage-challenge-token\"}"))
          .andExpect(status().isUnauthorized());
    }
    mockMvc.perform(post("/api/auth/2fa/passkeys/assertion/options")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"challenge\":\"garbage-challenge-token\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"));
  }

  @Test
  void assertionTrafficDoesNotStarveAuthenticatedRegistration() throws Exception {
    // Exhausting the assertion-options bucket on one IP leaves a fresh
    // user's JWT-keyed registration bucket untouched — the keying split
    // documented in the class javadoc, not a shared anonymous pool.
    String ip = "203.0.113.63";
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(post("/api/auth/2fa/passkeys/assertion/options")
              .with(fromIp(ip))
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"challenge\":\"garbage-challenge-token\"}"))
          .andExpect(status().isUnauthorized());
    }
    mockMvc.perform(post("/api/auth/2fa/passkeys/assertion/options")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"challenge\":\"garbage-challenge-token\"}"))
        .andExpect(status().isTooManyRequests());
    registrationOptionsOnce(bearer(freshUser()));
  }
}
