package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backlog #91 — TOTP recovery codes, end-to-end via MockMvc: enabling 2FA
 * issues 10 one-time codes (hashes only in the DB), a code exchanges a
 * login challenge exactly once (replay → the identical 401 as a wrong
 * TOTP code), TOTP codes keep working alongside, the count endpoint
 * reports what remains without ever exposing a code, and re-enrollment
 * invalidates the previous set.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users and codes it creates
class TotpRecoveryCodesTest {

  private static final AtomicInteger IP_SEQ = new AtomicInteger();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private TotpRecoveryCodeRepository recoveryCodes;

  @Autowired
  private TotpService totp;

  /** Distinct client IPs per call keep the per-IP login rate-limit bucket out of the way. */
  private static RequestPostProcessor uniqueIp() {
    return request -> {
      request.setRemoteAddr("10.91.0." + IP_SEQ.incrementAndGet());
      return request;
    };
  }

  private String register(String username) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/register")
            .with(uniqueIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                + "@example.com\",\"password\":\"s3cret-pass\"}"))
        .andExpect(status().isOk())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
  }

  private String setup(String accessToken) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/2fa/setup")
            .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.secret");
  }

  /** Enables 2FA and returns the recovery codes from the enable response. */
  private List<String> enable(String accessToken, String code) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/2fa/enable")
            .header("Authorization", "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + code + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.recoveryCodes");
  }

  /** Registers + enrolls a user; returns [accessToken, plainSecret, recoveryCodes...]. */
  private List<String> enroll(String username) throws Exception {
    String access = register(username);
    String secret = setup(access);
    List<String> codes = enable(access, currentCode(secret));
    java.util.List<String> out = new java.util.ArrayList<>();
    out.add(access);
    out.add(secret);
    out.addAll(codes);
    return out;
  }

  /** Logs in with the password and returns the 202 2FA challenge token. */
  private String loginChallenge(String username) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/login")
            .with(uniqueIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + username
                + "\",\"password\":\"s3cret-pass\"}"))
        .andExpect(status().isAccepted())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.challenge");
  }

  private void authenticate(String challenge, String code, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/auth/2fa/authenticate")
            .with(uniqueIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"challenge\":\"" + challenge + "\",\"code\":\"" + code + "\"}"))
        .andExpect(status().is(expectedStatus));
  }

  private long count(String accessToken) throws Exception {
    MvcResult result = mockMvc.perform(get("/api/auth/2fa/recovery-codes/count")
            .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andReturn();
    Integer remaining = JsonPath.read(result.getResponse().getContentAsString(), "$.data.remaining");
    return remaining.longValue();
  }

  private long userId(String username) {
    return users.findByUsernameIgnoreCase(username).orElseThrow().getId();
  }

  private String currentCode(String plainSecret) {
    return totp.codeAt(plainSecret, Instant.now());
  }

  @Test
  void enableIssuesTenCodesStoresHashesOnlyAndCountReportsThem() throws Exception {
    String access = register("carol");
    String secret = setup(access);
    List<String> codes = enable(access, currentCode(secret));

    assertThat(codes).hasSize(TotpService.RECOVERY_CODE_COUNT)
        .doesNotHaveDuplicates()
        .allMatch(c -> c.matches("[A-Z2-9]{4}(-[A-Z2-9]{4}){3}"));
    assertThat(count(access)).isEqualTo(10);

    List<TotpRecoveryCode> rows = recoveryCodes.findAll().stream()
        .filter(r -> r.getUserId().equals(userId("carol")))
        .toList();
    assertThat(rows).hasSize(10).allMatch(r -> !r.isUsed());
    // Hash-only storage: no stored value equals or contains a plaintext code,
    // and every row is exactly the SHA-256 of a normalized issued code.
    for (TotpRecoveryCode row : rows) {
      assertThat(row.getCodeHash()).hasSize(64);
      assertThat(codes).noneMatch(c -> c.equals(row.getCodeHash()));
    }
    assertThat(rows.stream().map(TotpRecoveryCode::getCodeHash))
        .containsExactlyInAnyOrderElementsOf(codes.stream()
            .map(c -> JwtTokenService.sha256Hex(TotpService.normalizeRecoveryCode(c)))
            .toList());
  }

  @Test
  void recoveryCodeAuthenticatesOnceThenReplayGets401() throws Exception {
    List<String> enrolled = enroll("dave");
    String access = enrolled.get(0);
    String code = enrolled.get(2);

    authenticate(loginChallenge("dave"), code, 200);
    assertThat(count(access)).isEqualTo(9);

    // Replay with a fresh challenge: consumed codes are dead — identical 401.
    authenticate(loginChallenge("dave"), code, 401);
    assertThat(count(access)).isEqualTo(9);
  }

  @Test
  void recoveryCodeTypedWithoutHyphensInLowercaseStillWorks() throws Exception {
    List<String> enrolled = enroll("erin");
    String typed = enrolled.get(2).replace("-", "").toLowerCase(java.util.Locale.ROOT);
    authenticate(loginChallenge("erin"), typed, 200);
  }

  @Test
  void totpCodesKeepWorkingAndWrongRecoveryCodeGetsIdentical401() throws Exception {
    List<String> enrolled = enroll("frank");
    String secret = enrolled.get(1);

    // A well-formed but never-issued recovery code → 401, nothing consumed.
    authenticate(loginChallenge("frank"), "ZZZZ-ZZZZ-ZZZZ-ZZZZ", 401);
    assertThat(count(enrolled.get(0))).isEqualTo(10);

    // TOTP still authenticates after recovery codes exist.
    authenticate(loginChallenge("frank"), currentCode(secret), 200);
    assertThat(count(enrolled.get(0))).isEqualTo(10);
  }

  @Test
  void reEnrollmentInvalidatesTheOldSet() throws Exception {
    List<String> enrolled = enroll("grace");
    String oldCode = enrolled.get(2);

    // Re-run setup + enable: a fresh secret and a fresh code set.
    String access = enrolled.get(0);
    String newSecret = setup(access);
    List<String> newCodes = enable(access, currentCode(newSecret));
    assertThat(newCodes).doesNotContain(oldCode);
    assertThat(count(access)).isEqualTo(10);

    authenticate(loginChallenge("grace"), oldCode, 401);
    authenticate(loginChallenge("grace"), newCodes.get(0), 200);
    assertThat(count(access)).isEqualTo(9);
  }

  @Test
  void countEndpointRequiresAuthAndDefaultsToZero() throws Exception {
    mockMvc.perform(get("/api/auth/2fa/recovery-codes/count"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    String access = register("heidi"); // never enrolled in 2FA
    assertThat(count(access)).isEqualTo(0);
  }

  @Test
  void generatorAndNormalizationUnits() {
    TotpService service = new TotpService(Clock.systemUTC());
    String code = service.generateRecoveryCode();
    assertThat(code).matches("[A-Z2-9]{4}(-[A-Z2-9]{4}){3}");
    assertThat(TotpService.normalizeRecoveryCode(code))
        .isEqualTo(code.replace("-", ""));
    assertThat(TotpService.normalizeRecoveryCode(code.replace("-", "").toLowerCase(
        java.util.Locale.ROOT))).isEqualTo(code.replace("-", ""));
    // Look-alike characters, TOTP shapes and junk can never be recovery codes.
    assertThat(TotpService.normalizeRecoveryCode("OOOO-OOOO-OOOO-OOOO")).isNull();
    assertThat(TotpService.normalizeRecoveryCode("123456")).isNull();
    assertThat(TotpService.normalizeRecoveryCode("short")).isNull();
    assertThat(TotpService.normalizeRecoveryCode(null)).isNull();
  }
}
