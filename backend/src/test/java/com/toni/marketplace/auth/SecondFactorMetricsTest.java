package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.AttestationConveyancePreference;
import com.webauthn4j.data.AuthenticatorAssertionResponse;
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
import com.webauthn4j.test.EmulatorUtil;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Backlog #336 — second-factor outcome counters. Each test measures the
 * delta on the shared test {@link MeterRegistry} across its own flow
 * (the AuthSecurityMetricsTest pattern: absolute values would couple
 * tests to execution order). The counting discipline mirrors #101: only
 * genuine code/assertion verification failures move a failure counter —
 * disabled accounts, unenrolled factors, proofs offered for a factor
 * the account does not hold, unknown credential ids and undecryptable
 * stored secrets leave every counter still.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SecondFactorMetricsTest {

  @TestConfiguration
  static class ControllableClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  private static final AtomicInteger SEQ = new AtomicInteger();
  private static final String PASSWORD = "s3cret-pass1";

  private static final String TOTP_SUCCESS = "auth.second_factor.totp.success";
  private static final String TOTP_FAILURE = "auth.second_factor.totp.failure";
  private static final String PASSKEY_SUCCESS = "auth.second_factor.passkey.success";
  private static final String PASSKEY_FAILURE = "auth.second_factor.passkey.failure";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private MeterRegistry registry;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private TotpService totp;

  @Autowired
  private AuthService auth;

  @Autowired
  private MutableClock clock;

  private final ObjectMapper json = new ObjectMapper();
  private final ObjectConverter webauthnConverter = new ObjectConverter();

  private record TotpUser(String username, String secret, List<String> recoveryCodes) {}

  /** Current count for a counter, 0 when it has never been registered/fired. */
  private double count(String name) {
    Counter counter = registry.find(name).counter();
    return counter == null ? 0.0 : counter.count();
  }

  // ---- TOTP helpers (the TotpAuthenticateLockoutTest flow) ----

  private TotpUser enrollTotp() throws Exception {
    String username = "sfmetrics-" + SEQ.incrementAndGet();
    MvcResult registered = mockMvc.perform(post("/api/auth/register")
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
    String secret = JsonPath.read(setup.getResponse().getContentAsString(), "$.data.secret");
    MvcResult enabled = mockMvc.perform(post("/api/auth/2fa/enable")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + totp.codeAt(secret, clock.instant()) + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    List<String> recoveryCodes = JsonPath.read(enabled.getResponse().getContentAsString(),
        "$.data.recoveryCodes");
    return new TotpUser(username, secret, recoveryCodes);
  }

  /** Password login on a TOTP-enrolled account: returns the 202 challenge token. */
  private String totpLoginChallenge(TotpUser user) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + user.username()
                + "\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isAccepted())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.challenge");
  }

  private void authenticateCode(String challenge, String code, int expected) throws Exception {
    mockMvc.perform(post("/api/auth/2fa/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"challenge\":\"" + challenge + "\",\"code\":\"" + code + "\"}"))
        .andExpect(status().is(expected));
  }

  /** A 6-digit code that verifies in no accepted time step (and is no recovery code). */
  private String wrongCode(String secret) {
    Set<String> valid = Set.of(
        totp.codeAt(secret, clock.instant().minusSeconds(30)),
        totp.codeAt(secret, clock.instant()),
        totp.codeAt(secret, clock.instant().plusSeconds(30)));
    for (String candidate : List.of("000000", "111111", "222222", "333333", "444444")) {
      if (!valid.contains(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("no wrong code candidate");
  }

  // ---- Passkey helpers (the PasskeyCeremonyTest flow) ----

  private User freshPasskeyUser() {
    String tag = "sfmetrics-pk-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private ClientPlatform clientPlatform() {
    return clientPlatform("http://localhost:5173");
  }

  private ClientPlatform clientPlatform(String origin) {
    return new ClientPlatform(new Origin(origin),
        new WebAuthnAuthenticatorAdaptor(EmulatorUtil.NONE_ATTESTATION_AUTHENTICATOR,
            webauthnConverter));
  }

  private String passkeyBearer(User user) {
    JwtTokenService.TokenPair pair =
        ((AuthService.LoginResult.Pair) auth.login(user.getUsername(), "password"))
            .result().pair();
    return pair.accessToken();
  }

  private JsonNode dataOf(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString()).path("data");
  }

  private void enrollPasskey(User user, String bearer) throws Exception {
    MvcResult options = mockMvc.perform(post("/api/auth/2fa/passkeys/registration/options")
            .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode data = dataOf(options);
    byte[] challengeBytes = PasskeyService.base64UrlDecode(data.path("challenge").asText());
    List<PublicKeyCredentialDescriptor> excludeCredentials =
        jsonList(data, "excludeCredentials").stream()
            .map(id -> new PublicKeyCredentialDescriptor(PublicKeyCredentialType.PUBLIC_KEY,
                PasskeyService.base64UrlDecode(id), Set.of()))
            .toList();
    PublicKeyCredentialCreationOptions creationOptions =
        new PublicKeyCredentialCreationOptions(
            new PublicKeyCredentialRpEntity(data.path("rpId").asText(),
                data.path("rpName").asText()),
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
    String credentialJson = webauthnConverter.getJsonConverter()
        .writeValueAsString(clientPlatform().create(creationOptions));
    mockMvc.perform(post("/api/auth/2fa/passkeys/registration/verify")
            .header("Authorization", "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("credential", credentialJson))))
        .andExpect(status().isOk());
  }

  /** Password login on a passkey-enrolled account: returns the 202 challenge token. */
  private String passkeyLoginChallenge(User user) throws Exception {
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

  private String buildAssertion(String loginChallenge, ClientPlatform platform) throws Exception {
    MvcResult options = mockMvc.perform(post("/api/auth/2fa/passkeys/assertion/options")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("challenge", loginChallenge))))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode data = dataOf(options);
    byte[] challengeBytes = PasskeyService.base64UrlDecode(data.path("challenge").asText());
    List<PublicKeyCredentialDescriptor> allowCredentials = jsonList(data, "allowCredentials")
        .stream()
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
    PublicKeyCredential<AuthenticatorAssertionResponse, AuthenticationExtensionClientOutput>
        assertion = platform.get(requestOptions);
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

  // ---- Counter behaviour ----

  @Test
  void metricsOnThrowawayRegistryMoveExactlyOncePerOutcome() {
    // The throwaway registry pins the meter names the README documents:
    // each method moves exactly its own counter, once.
    MeterRegistry throwaway = new SimpleMeterRegistry();
    AuthMetrics pinned = new AuthMetrics(throwaway);
    pinned.secondFactorTotpSuccess();
    assertThat(throwaway.find(TOTP_SUCCESS).counter().count()).isEqualTo(1.0);
    assertThat(throwaway.find(TOTP_FAILURE).counter().count()).isEqualTo(0.0);
    pinned.secondFactorTotpFailure();
    assertThat(throwaway.find(TOTP_FAILURE).counter().count()).isEqualTo(1.0);
    pinned.secondFactorPasskeySuccess();
    assertThat(throwaway.find(PASSKEY_SUCCESS).counter().count()).isEqualTo(1.0);
    pinned.secondFactorPasskeyFailure();
    assertThat(throwaway.find(PASSKEY_FAILURE).counter().count()).isEqualTo(1.0);
  }

  @Test
  void totpSuccessMovesOnlyTheTotpSuccessCounter() throws Exception {
    TotpUser user = enrollTotp();
    double successBefore = count(TOTP_SUCCESS);
    double failureBefore = count(TOTP_FAILURE);
    double pkSuccessBefore = count(PASSKEY_SUCCESS);
    double pkFailureBefore = count(PASSKEY_FAILURE);

    authenticateCode(totpLoginChallenge(user),
        totp.codeAt(user.secret(), clock.instant()), 200);

    assertThat(count(TOTP_SUCCESS)).isEqualTo(successBefore + 1);
    assertThat(count(TOTP_FAILURE)).isEqualTo(failureBefore);
    assertThat(count(PASSKEY_SUCCESS)).isEqualTo(pkSuccessBefore);
    assertThat(count(PASSKEY_FAILURE)).isEqualTo(pkFailureBefore);
  }

  @Test
  void totpFailureMovesOnlyTheTotpFailureCounter() throws Exception {
    TotpUser user = enrollTotp();
    double successBefore = count(TOTP_SUCCESS);
    double failureBefore = count(TOTP_FAILURE);

    authenticateCode(totpLoginChallenge(user), wrongCode(user.secret()), 401);

    assertThat(count(TOTP_FAILURE)).isEqualTo(failureBefore + 1);
    assertThat(count(TOTP_SUCCESS)).isEqualTo(successBefore);
  }

  @Test
  void recoveryCodeCompletionCountsAsTotpSuccess() throws Exception {
    TotpUser user = enrollTotp();
    double successBefore = count(TOTP_SUCCESS);
    double failureBefore = count(TOTP_FAILURE);

    authenticateCode(totpLoginChallenge(user), user.recoveryCodes().get(0), 200);

    assertThat(count(TOTP_SUCCESS)).isEqualTo(successBefore + 1);
    assertThat(count(TOTP_FAILURE)).isEqualTo(failureBefore);
  }

  @Test
  void totpUndecryptableSecretLeavesCountersStill() throws Exception {
    TotpUser user = enrollTotp();
    double successBefore = count(TOTP_SUCCESS);
    double failureBefore = count(TOTP_FAILURE);

    // Undecryptable stored secret: a v1: value that cannot parse fails
    // closed as 401 without ever verifying a code.
    User row = users.findByUsernameIgnoreCase(user.username()).orElseThrow();
    row.setTotpSecret("v1:garbage");
    users.save(row);
    authenticateCode(totpLoginChallenge(user), wrongCode(user.secret()), 401);
    assertThat(count(TOTP_SUCCESS)).isEqualTo(successBefore);
    assertThat(count(TOTP_FAILURE)).isEqualTo(failureBefore);
  }

  @Test
  void totpDisabledAccountExchangeLeavesCountersStill() throws Exception {
    TotpUser user = enrollTotp();
    String challenge = totpLoginChallenge(user);
    User row = users.findByUsernameIgnoreCase(user.username()).orElseThrow();
    row.setDisabled(true);
    users.save(row);

    double successBefore = count(TOTP_SUCCESS);
    double failureBefore = count(TOTP_FAILURE);
    authenticateCode(challenge, totp.codeAt(user.secret(), clock.instant()), 401);
    assertThat(count(TOTP_SUCCESS)).isEqualTo(successBefore);
    assertThat(count(TOTP_FAILURE)).isEqualTo(failureBefore);
  }

  @Test
  void dispatcherFactorMismatchLeavesCountersStill() throws Exception {
    // Passkey-only account offered a code: the dispatcher refuses before
    // any TOTP verification.
    User pkUser = freshPasskeyUser();
    enrollPasskey(pkUser, passkeyBearer(pkUser));
    double totpSuccessBefore = count(TOTP_SUCCESS);
    double totpFailureBefore = count(TOTP_FAILURE);
    authenticateCode(passkeyLoginChallenge(pkUser), "000000", 401);
    assertThat(count(TOTP_SUCCESS)).isEqualTo(totpSuccessBefore);
    assertThat(count(TOTP_FAILURE)).isEqualTo(totpFailureBefore);

    // TOTP-only account offered an assertion: the dispatcher refuses
    // before any passkey verification (the assertion body is never
    // parsed on this path).
    TotpUser totpUser = enrollTotp();
    double pkSuccessBefore = count(PASSKEY_SUCCESS);
    double pkFailureBefore = count(PASSKEY_FAILURE);
    MvcResult result = mockMvc.perform(post("/api/auth/2fa/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of(
                "challenge", totpLoginChallenge(totpUser), "assertion", "{}"))))
        .andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(401);
    assertThat(count(PASSKEY_SUCCESS)).isEqualTo(pkSuccessBefore);
    assertThat(count(PASSKEY_FAILURE)).isEqualTo(pkFailureBefore);
  }

  @Test
  void passkeySuccessAndFailureMoveOnlyThePasskeyCounters() throws Exception {
    User user = freshPasskeyUser();
    enrollPasskey(user, passkeyBearer(user));
    double successBefore = count(PASSKEY_SUCCESS);
    double failureBefore = count(PASSKEY_FAILURE);
    double totpSuccessBefore = count(TOTP_SUCCESS);
    double totpFailureBefore = count(TOTP_FAILURE);

    // A fresh challenge + a matching assertion is the success path.
    String challenge = passkeyLoginChallenge(user);
    String assertion = buildAssertion(challenge, clientPlatform());
    assertThat(authenticateWithAssertion(challenge, assertion)
        .getResponse().getStatus()).isEqualTo(200);
    assertThat(count(PASSKEY_SUCCESS)).isEqualTo(successBefore + 1);
    assertThat(count(PASSKEY_FAILURE)).isEqualTo(failureBefore);

    // Wrong-origin assertion: a genuine ceremony verification failure.
    String badChallenge = passkeyLoginChallenge(user);
    String badAssertion = buildAssertion(badChallenge, clientPlatform("http://evil.example"));
    assertThat(authenticateWithAssertion(badChallenge, badAssertion)
        .getResponse().getStatus()).isEqualTo(401);
    assertThat(count(PASSKEY_FAILURE)).isEqualTo(failureBefore + 1);
    assertThat(count(PASSKEY_SUCCESS)).isEqualTo(successBefore + 1);
    assertThat(count(TOTP_SUCCESS)).isEqualTo(totpSuccessBefore);
    assertThat(count(TOTP_FAILURE)).isEqualTo(totpFailureBefore);
  }

  @Test
  void passkeyUnknownCredentialLeavesCountersStill() throws Exception {
    User user = freshPasskeyUser();
    enrollPasskey(user, passkeyBearer(user));
    String challenge = passkeyLoginChallenge(user);
    String assertion = buildAssertion(challenge, clientPlatform());
    JsonNode tampered = json.readTree(assertion);
    ((com.fasterxml.jackson.databind.node.ObjectNode) tampered)
        .put("id", PasskeyService.base64UrlEncode(new byte[32]));

    double successBefore = count(PASSKEY_SUCCESS);
    double failureBefore = count(PASSKEY_FAILURE);
    assertThat(authenticateWithAssertion(challenge, tampered.toString())
        .getResponse().getStatus()).isEqualTo(401);
    assertThat(count(PASSKEY_SUCCESS)).isEqualTo(successBefore);
    assertThat(count(PASSKEY_FAILURE)).isEqualTo(failureBefore);
  }
}
