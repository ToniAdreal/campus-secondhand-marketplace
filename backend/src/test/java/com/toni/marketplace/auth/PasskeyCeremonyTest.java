package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.common.InvalidCredentialsException;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.AttestationConveyancePreference;
import com.webauthn4j.data.AuthenticatorAssertionResponse;
import com.webauthn4j.data.AuthenticatorAttestationResponse;
import com.webauthn4j.data.AuthenticatorSelectionCriteria;
import com.webauthn4j.data.PublicKeyCredential;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialDescriptor;
import com.webauthn4j.data.PublicKeyCredentialRequestOptions;
import com.webauthn4j.data.PublicKeyCredentialRpEntity;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.PublicKeyCredentialUserEntity;
import com.webauthn4j.data.ResidentKeyRequirement;
import com.webauthn4j.data.UserVerificationRequirement;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.data.extension.client.AuthenticationExtensionClientOutput;
import com.webauthn4j.data.extension.client.AuthenticationExtensionsClientInputs;
import com.webauthn4j.data.extension.client.RegistrationExtensionClientInput;
import com.webauthn4j.data.extension.client.RegistrationExtensionClientOutput;
import com.webauthn4j.test.EmulatorUtil;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * WebAuthn passkeys as an alternative second factor (backlog #220),
 * end to end through MockMvc with a software authenticator + client
 * platform from webauthn4j-test playing the browser
 * ({@code navigator.credentials.create/get}).
 *
 * <p>Pinned here:
 * <ul>
 *   <li>the enrolment ceremony: options → software-authenticator
 *       {@code create} → verify → 200, and the credential row is
 *       persisted (id, COSE public key, initial counter) with a
 *       {@code PASSKEY_REGISTERED} audit row;</li>
 *   <li>challenges are single-use: the same registration ceremony
 *       posted twice verifies once and answers 401 the second time;</li>
 *   <li>enrolment and removal are step-up gated: a stale token gets
 *       the 403 re-authentication envelope on the options call;</li>
 *   <li>the login-time ceremony: password login on a passkey-enrolled
 *       account answers 202 advertising {@code "passkey"} in
 *       {@code factors}; assertion options → software-authenticator
 *       {@code get} → the {@code /2fa/authenticate} exchange answers
 *       200 with a token pair, exactly like the TOTP leg;</li>
 *   <li>the negative matrix — replayed assertion challenge, wrong
 *       origin, unknown credential id, garbage 202 challenge on the
 *       assertion-options endpoint — all answer the identical 401,
 *       never a reason;</li>
 *   <li>the signature-counter clone rule, unit-pinned:
 *       {@link PasskeyService#checkCounterNotRegressed};</li>
 *   <li>credential management: list shows the enrolled passkey
 *       without the public key; deleting another user's row id (or a
 *       missing one) is 404 with no cross-user oracle.</li>
 * </ul>
 *
 * <p>The application {@code Clock} bean is replaced with a
 * {@link MutableClock} so challenge TTL and step-up aging are
 * deterministic. (The shared test profile disables the rate limiter.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PasskeyCeremonyTest {

  @TestConfiguration
  static class ControllableClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordService passwords;
  @Autowired private AuthService auth;
  @Autowired private PasskeyCredentialRepository passkeyCredentials;
  @Autowired private AuditLogRepository auditLogs;
  @Autowired private MutableClock clock;

  private final ObjectMapper json = new ObjectMapper();
  private final ObjectConverter webauthnConverter = new ObjectConverter();

  private User freshUser() {
    String tag = "passkey-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  /** A software client platform whose origin the server accepts. */
  private ClientPlatform clientPlatform() {
    return clientPlatform("http://localhost:5173");
  }

  private ClientPlatform clientPlatform(String origin) {
    return new ClientPlatform(new Origin(origin),
        new WebAuthnAuthenticatorAdaptor(EmulatorUtil.NONE_ATTESTATION_AUTHENTICATOR,
            webauthnConverter));
  }

  private String bearer(User user) {
    JwtTokenService.TokenPair pair =
        ((AuthService.LoginResult.Pair) auth.login(user.getUsername(), "password"))
            .result().pair();
    return pair.accessToken();
  }

  /**
   * A Bearer <redacted> for a second-factor-enrolled account (backlog #220): the
   * password stage answers 202, so the token pair is completed through
   * the passkey assertion exchange, exactly like a real SPA login.
   */
  private String passkeyBearer(User user) throws Exception {
    String loginChallenge = loginChallenge(user);
    String assertionJson = buildAssertion(user, loginChallenge, clientPlatform());
    MvcResult result = authenticateWithAssertion(loginChallenge, assertionJson);
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    return dataOf(result).path("accessToken").asText();
  }

  private JsonNode dataOf(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString()).path("data");
  }

  /** Drives options → create → verify through MockMvc; returns the enrolled credential id. */
  private String enrollPasskey(User user, String bearer) throws Exception {
    return enrollPasskey(user, bearer, null);
  }

  /**
   * The named variant (backlog #241): the verify body carries the
   * optional friendly name, exactly as the SPA sends it; a null name
   * is omitted, matching an unnamed enrolment.
   */
  private String enrollPasskey(User user, String bearer, String name) throws Exception {
    MvcResult options = mockMvc.perform(post("/api/auth/2fa/passkeys/registration/options")
            .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode data = dataOf(options);
    String credentialJson = webauthnConverter.getJsonConverter()
        .writeValueAsString(clientPlatform().create(creationOptions(data)));
    java.util.Map<String, Object> body = new java.util.HashMap<>();
    body.put("credential", credentialJson);
    if (name != null) {
      body.put("name", name);
    }
    MvcResult verify = mockMvc.perform(post("/api/auth/2fa/passkeys/registration/verify")
            .header("Authorization", "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)))
        .andExpect(status().isOk())
        .andReturn();
    String credentialId = dataOf(verify).path("credentialId").asText();
    assertThat(credentialId).isNotBlank();
    return credentialId;
  }

  /**
   * The {@code navigator.credentials.create({publicKey: ...})} input the
   * server's registration options describe — a second factor, so
   * resident keys are discouraged and user verification is preferred
   * (presence is what the server requires).
   */
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

  /** Password login on a passkey-enrolled account: returns the 202 challenge token. */
  private String loginChallenge(User user) throws Exception {
    MvcResult login = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(
                Map.of("usernameOrEmail", user.getUsername(), "password", "password"))))
        .andExpect(status().isAccepted())
        .andReturn();
    JsonNode data = dataOf(login);
    assertThat(data.path("factors").toString()).contains("passkey");
    return data.path("challenge").asText();
  }

  /** Drives assertion-options → get → authenticate; returns the raw assertion JSON. */
  private String buildAssertion(User user, String loginChallenge, ClientPlatform platform)
      throws Exception {
    MvcResult options = mockMvc.perform(post("/api/auth/2fa/passkeys/assertion/options")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("challenge", loginChallenge))))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode data = dataOf(options);
    byte[] challengeBytes = PasskeyService.base64UrlDecode(data.path("challenge").asText());
    List<PublicKeyCredentialDescriptor> allowCredentials = jsonList(data, "allowCredentials").stream()
        .map(id -> new PublicKeyCredentialDescriptor(PublicKeyCredentialType.PUBLIC_KEY,
            PasskeyService.base64UrlDecode(id), Set.of()))
        .toList();
    PublicKeyCredentialRequestOptions requestOptions =
        new PublicKeyCredentialRequestOptions(
            new DefaultChallenge(challengeBytes),
            data.path("timeout").asLong(),
            data.path("rpId").asText(),
            allowCredentials,
            UserVerificationRequirement.PREFERRED,
            new AuthenticationExtensionsClientInputs<>());
    PublicKeyCredential<AuthenticatorAssertionResponse, AuthenticationExtensionClientOutput> assertion =
        platform.get(requestOptions);
    return webauthnConverter.getJsonConverter().writeValueAsString(assertion);
  }

  private List<String> jsonList(JsonNode data, String field) {
    return json.convertValue(data.path(field),
        json.getTypeFactory().constructCollectionType(List.class, String.class));
  }

  private MvcResult authenticateWithAssertion(String loginChallenge, String assertionJson)
      throws Exception {
    return mockMvc.perform(post("/api/auth/2fa/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(
                Map.of("challenge", loginChallenge, "assertion", assertionJson))))
        .andReturn();
  }

  @Test
  void registrationHappyPathPersistsCredentialAndAudits() throws Exception {
    User user = freshUser();
    String credentialId = enrollPasskey(user, bearer(user));

    assertThat(passkeyCredentials.countByUserId(user.getId())).isEqualTo(1);
    PasskeyCredential row = passkeyCredentials.findByCredentialId(credentialId).orElseThrow();
    assertThat(row.getUserId()).isEqualTo(user.getId());
    assertThat(row.getPublicKeyCose()).isNotEmpty();
    assertThat(auditLogs.findByActorUserIdOrderByIdAsc(user.getId())).anySatisfy(
        log -> assertThat(log.getAction()).isEqualTo(AuditAction.PASSKEY_REGISTERED));
  }

  @Test
  void registrationChallengeIsSingleUse() throws Exception {
    User user = freshUser();
    String bearer = bearer(user);
    MvcResult options = mockMvc.perform(post("/api/auth/2fa/passkeys/registration/options")
            .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andReturn();
    String credentialJson = webauthnConverter.getJsonConverter()
        .writeValueAsString(clientPlatform().create(creationOptions(dataOf(options))));
    String body = json.writeValueAsString(Map.of("credential", credentialJson));

    mockMvc.perform(post("/api/auth/2fa/passkeys/registration/verify")
            .header("Authorization", "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk());

    // The same ceremony posted again: the challenge was consumed by the
    // first verify — the identical 401, and no second credential row.
    mockMvc.perform(post("/api/auth/2fa/passkeys/registration/verify")
            .header("Authorization", "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isUnauthorized());
    assertThat(passkeyCredentials.countByUserId(user.getId())).isEqualTo(1);
  }

  @Test
  void registrationOptionsRequiresFreshProof() throws Exception {
    User user = freshUser();
    String bearer = bearer(user);
    clock.advance(Duration.ofMinutes(11)); // past the 10-minute step-up max-age

    MvcResult result = mockMvc.perform(post("/api/auth/2fa/passkeys/registration/options")
            .header("Authorization", "Bearer " + bearer))
        .andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(403);
    assertThat(result.getResponse().getContentAsString()).contains("re-authentication required");
  }

  @Test
  void assertionHappyPathCompletesLogin() throws Exception {
    User user = freshUser();
    enrollPasskey(user, bearer(user));

    String loginChallenge = loginChallenge(user);
    String assertionJson = buildAssertion(user, loginChallenge, clientPlatform());

    MvcResult result = authenticateWithAssertion(loginChallenge, assertionJson);
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    JsonNode data = dataOf(result);
    assertThat(data.path("accessToken").asText()).isNotBlank();
  }

  @Test
  void assertionChallengeIsSingleUse() throws Exception {
    User user = freshUser();
    enrollPasskey(user, bearer(user));

    String loginChallenge = loginChallenge(user);
    String assertionJson = buildAssertion(user, loginChallenge, clientPlatform());
    assertThat(authenticateWithAssertion(loginChallenge, assertionJson)
        .getResponse().getStatus()).isEqualTo(200);

    // The same assertion posted again: the challenge was consumed by the
    // first exchange — the identical 401.
    assertThat(authenticateWithAssertion(loginChallenge, assertionJson)
        .getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void assertionWithWrongOriginIsRejected() throws Exception {
    User user = freshUser();
    enrollPasskey(user, bearer(user));

    String loginChallenge = loginChallenge(user);
    String assertionJson = buildAssertion(user, loginChallenge,
        clientPlatform("http://evil.example"));

    assertThat(authenticateWithAssertion(loginChallenge, assertionJson)
        .getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void assertionWithUnknownCredentialIdIsRejected() throws Exception {
    User user = freshUser();
    enrollPasskey(user, bearer(user));

    String loginChallenge = loginChallenge(user);
    String assertionJson = buildAssertion(user, loginChallenge, clientPlatform());
    JsonNode tampered = json.readTree(assertionJson);
    // Swap the credential id for one never enrolled: lookup fails before
    // verification, the challenge stays live, the answer is the 401.
    ((com.fasterxml.jackson.databind.node.ObjectNode) tampered)
        .put("id", PasskeyService.base64UrlEncode(new byte[32]));

    assertThat(authenticateWithAssertion(loginChallenge, tampered.toString())
        .getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void assertionOptionsWithGarbageChallengeIsRejected() throws Exception {
    mockMvc.perform(post("/api/auth/2fa/passkeys/assertion/options")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("challenge", "garbage-challenge-token"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void counterNotRegressedWhenZeroOrAdvancing() {
    // Platform authenticators (Apple/Google passkeys) never implement
    // the counter and always report 0 — exempt from the clone rule.
    PasskeyService.checkCounterNotRegressed(5, 0);
    PasskeyService.checkCounterNotRegressed(0, 0);
    PasskeyService.checkCounterNotRegressed(5, 6);
  }

  @Test
  void counterRegressionIsRejected() {
    // A non-zero counter that fails to advance past the stored value
    // means the credential was very likely cloned — the identical 401.
    assertThatThrownBy(() -> PasskeyService.checkCounterNotRegressed(5, 5))
        .isInstanceOf(InvalidCredentialsException.class);
    assertThatThrownBy(() -> PasskeyService.checkCounterNotRegressed(5, 3))
        .isInstanceOf(InvalidCredentialsException.class);
  }

  @Test
  void listPasskeysHidesPublicKeyAndDeleteIsUserScoped() throws Exception {
    User user = freshUser();
    User other = freshUser();
    String credentialId = enrollPasskey(user, bearer(user));

    MvcResult list = mockMvc.perform(get("/api/auth/2fa/passkeys")
            .header("Authorization", "Bearer " + passkeyBearer(user)))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode rows = dataOf(list);
    assertThat(rows.size()).isEqualTo(1);
    assertThat(rows.get(0).path("credentialId").asText()).isEqualTo(credentialId);
    assertThat(rows.get(0).has("publicKeyCose")).isFalse();
    long rowId = rows.get(0).path("id").asLong();

    // Another user's row id — and a missing one — are 404, no cross-user oracle.
    mockMvc.perform(delete("/api/auth/2fa/passkeys/" + rowId)
            .header("Authorization", "Bearer " + bearer(other)))
        .andExpect(status().isNotFound());
    mockMvc.perform(delete("/api/auth/2fa/passkeys/999999")
            .header("Authorization", "Bearer " + passkeyBearer(user)))
        .andExpect(status().isNotFound());
    assertThat(passkeyCredentials.countByUserId(user.getId())).isEqualTo(1);

    // The owner removes it — but only with a fresh proof (step-up gate).
    // The token below is minted fresh, then the clock advances past the
    // step-up window: the same token must then read as stale (403), and
    // only a newly-minted proof succeeds (200).
    String staleProof = passkeyBearer(user);
    clock.advance(Duration.ofMinutes(11));
    MvcResult staleDelete = mockMvc.perform(delete("/api/auth/2fa/passkeys/" + rowId)
            .header("Authorization", "Bearer " + staleProof))
        .andReturn();
    assertThat(staleDelete.getResponse().getStatus()).isEqualTo(403);

    mockMvc.perform(delete("/api/auth/2fa/passkeys/" + rowId)
            .header("Authorization", "Bearer " + passkeyBearer(user)))
        .andExpect(status().isOk());
    assertThat(passkeyCredentials.countByUserId(user.getId())).isZero();
    assertThat(auditLogs.findByActorUserIdOrderByIdAsc(user.getId())).anySatisfy(
        log -> assertThat(log.getAction()).isEqualTo(AuditAction.PASSKEY_REMOVED));
  }

  @Test
  void passkeyFriendlyNamePersistsRenamesAndBlankFallsBack() throws Exception {
    // An unnamed enrolment stores NULL, not a blank string — the
    // settings surface's date label is the fallback for exactly this.
    User unnamed = freshUser();
    enrollPasskey(unnamed, bearer(unnamed));
    MvcResult unnamedList = mockMvc.perform(get("/api/auth/2fa/passkeys")
            .header("Authorization", "Bearer " + passkeyBearer(unnamed)))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode unnamedRows = dataOf(unnamedList);
    assertThat(unnamedRows.size()).isEqualTo(1);
    assertThat(unnamedRows.get(0).path("name").isNull()).isTrue();

    // A name given at enrolment persists, trimmed, and never exposes key material.
    User user = freshUser();
    enrollPasskey(user, bearer(user), "  My phone  ");
    MvcResult list = mockMvc.perform(get("/api/auth/2fa/passkeys")
            .header("Authorization", "Bearer " + passkeyBearer(user)))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode rows = dataOf(list);
    assertThat(rows.size()).isEqualTo(1);
    assertThat(rows.get(0).path("name").asText()).isEqualTo("My phone");
    assertThat(rows.get(0).has("publicKeyCose")).isFalse();
    long rowId = rows.get(0).path("id").asLong();

    // Rename updates the listed row.
    MvcResult renamed = mockMvc.perform(patch("/api/auth/2fa/passkeys/" + rowId)
            .header("Authorization", "Bearer " + passkeyBearer(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "Work laptop"))))
        .andExpect(status().isOk())
        .andReturn();
    assertThat(dataOf(renamed).path("name").asText()).isEqualTo("Work laptop");

    // Another user's row id — and a missing one — are 404, no cross-user oracle.
    User other = freshUser();
    mockMvc.perform(patch("/api/auth/2fa/passkeys/" + rowId)
            .header("Authorization", "Bearer " + bearer(other))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "Not yours"))))
        .andExpect(status().isNotFound());
    mockMvc.perform(patch("/api/auth/2fa/passkeys/999999")
            .header("Authorization", "Bearer " + passkeyBearer(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "Ghost"))))
        .andExpect(status().isNotFound());

    // An overlong name is a 400, never a silent truncation.
    mockMvc.perform(patch("/api/auth/2fa/passkeys/" + rowId)
            .header("Authorization", "Bearer " + passkeyBearer(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "x".repeat(101)))))
        .andExpect(status().isBadRequest());

    // A blank rename clears the label back to NULL — the row falls
    // back to the date label instead of rendering blank.
    MvcResult cleared = mockMvc.perform(patch("/api/auth/2fa/passkeys/" + rowId)
            .header("Authorization", "Bearer " + passkeyBearer(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "   "))))
        .andExpect(status().isOk())
        .andReturn();
    assertThat(dataOf(cleared).path("name").isNull()).isTrue();

    // A stale proof cannot rename (step-up gate, like enrolment/removal).
    String staleProof = passkeyBearer(user);
    clock.advance(Duration.ofMinutes(11));
    mockMvc.perform(patch("/api/auth/2fa/passkeys/" + rowId)
            .header("Authorization", "Bearer " + staleProof)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "Stale"))))
        .andExpect(status().isForbidden());

    // Removal still fires the PASSKEY_REMOVED alert after renames.
    mockMvc.perform(delete("/api/auth/2fa/passkeys/" + rowId)
            .header("Authorization", "Bearer " + passkeyBearer(user)))
        .andExpect(status().isOk());
    assertThat(passkeyCredentials.countByUserId(user.getId())).isZero();
    assertThat(auditLogs.findByActorUserIdOrderByIdAsc(user.getId())).anySatisfy(
        log -> assertThat(log.getAction()).isEqualTo(AuditAction.PASSKEY_REMOVED));
  }
}
