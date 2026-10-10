package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.audit.AuditTargetType;
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
 * Backlog #127 — regenerating the TOTP recovery-code set without
 * re-enrolling, end-to-end via MockMvc: the regenerate demands the
 * current password AND a second factor (TOTP code or an unused recovery
 * code — the same proof seam as the #121 disable), deletes the whole
 * old set, issues 10 fresh codes shown once, leaves the TOTP secret and
 * the enabled flag untouched, and writes a
 * {@code RECOVERY_CODES_REGENERATED} audit row. Wrong proofs get the
 * identical 401 and move nothing; regenerating when 2FA is off is 422.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users, codes and audit rows it creates
class TotpRecoveryCodesRegenerateTest {

  private static final AtomicInteger IP_SEQ = new AtomicInteger();
  private static final String PASSWORD = "s3cret-pass";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private AuditLogRepository auditLog;

  @Autowired
  private TotpService totp;

  /** Distinct client IPs per call keep the per-IP login rate-limit bucket out of the way. */
  private static RequestPostProcessor uniqueIp() {
    return request -> {
      request.setRemoteAddr("10.127.0." + IP_SEQ.incrementAndGet());
      return request;
    };
  }

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

  /** Registers + enrolls a user; returns [accessToken, plainSecret, recoveryCodes...]. */
  private List<String> enroll(String username) throws Exception {
    String access = register(username);
    MvcResult setup = mockMvc.perform(post("/api/auth/2fa/setup")
            .header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andReturn();
    String secret = JsonPath.read(setup.getResponse().getContentAsString(), "$.data.secret");
    MvcResult enabled = mockMvc.perform(post("/api/auth/2fa/enable")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + currentCode(secret) + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    List<String> codes = JsonPath.read(enabled.getResponse().getContentAsString(),
        "$.data.recoveryCodes");
    java.util.List<String> out = new java.util.ArrayList<>();
    out.add(access);
    out.add(secret);
    out.addAll(codes);
    return out;
  }

  /** Regenerates with the given proofs; asserts the status and returns the new codes on 200. */
  private List<String> regenerate(String accessToken, String password, String code, int expected)
      throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/2fa/recovery-codes/regenerate")
            .header("Authorization", "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + password + "\",\"code\":\"" + code + "\"}"))
        .andExpect(status().is(expected))
        .andReturn();
    if (expected != 200) {
      return List.of();
    }
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.recoveryCodes");
  }

  /** Logs in with the password and returns the 202 2FA challenge token. */
  private String loginChallenge(String username) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/login")
            .with(uniqueIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + username
                + "\",\"password\":\"" + PASSWORD + "\"}"))
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

  /** A 6-digit code that verifies in no accepted time step (and is no recovery code). */
  private String wrongCode(String plainSecret) {
    java.util.Set<String> valid = java.util.Set.of(
        totp.codeAt(plainSecret, Instant.now().minusSeconds(30)),
        totp.codeAt(plainSecret, Instant.now()),
        totp.codeAt(plainSecret, Instant.now().plusSeconds(30)));
    for (String candidate : List.of("000000", "111111", "222222", "333333", "444444")) {
      if (!valid.contains(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("no wrong code candidate");
  }

  @Test
  void regenerateWithTotpCodeReplacesTheSetAndKeepsTheEnrollment() throws Exception {
    List<String> enrolled = enroll("olga");
    String access = enrolled.get(0);
    String secret = enrolled.get(1);
    List<String> oldCodes = enrolled.subList(2, enrolled.size());
    String storedSecretBefore =
        users.findById(userId("olga")).orElseThrow().getTotpSecret();

    List<String> fresh = regenerate(access, PASSWORD, currentCode(secret), 200);

    assertThat(fresh).hasSize(TotpService.RECOVERY_CODE_COUNT)
        .doesNotHaveDuplicates()
        .allMatch(c -> c.matches("[A-Z2-9]{4}(-[A-Z2-9]{4}){3}"))
        .doesNotContainAnyElementsOf(oldCodes);
    assertThat(count(access)).isEqualTo(10);

    // No re-enrollment: 2FA stays on and the stored secret is untouched,
    // so the same authenticator app keeps producing valid codes.
    User user = users.findById(userId("olga")).orElseThrow();
    assertThat(user.isTotpEnabled()).isTrue();
    assertThat(user.getTotpSecret()).isEqualTo(storedSecretBefore);
    authenticate(loginChallenge("olga"), currentCode(secret), 200);

    // The old set is dead (identical 401) and a fresh code works once.
    authenticate(loginChallenge("olga"), oldCodes.get(0), 401);
    authenticate(loginChallenge("olga"), fresh.get(0), 200);
    assertThat(count(access)).isEqualTo(9);
    authenticate(loginChallenge("olga"), fresh.get(0), 401);

    // Exactly one RECOVERY_CODES_REGENERATED audit row, actor = target.
    var rows = auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(
        AuditTargetType.USER, user.getId()).stream()
        .filter(r -> r.getAction() == AuditAction.RECOVERY_CODES_REGENERATED)
        .toList();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getActorUserId()).isEqualTo(user.getId());
  }

  @Test
  void regenerateWithRecoveryCodeProofConsumesTheOldSet() throws Exception {
    List<String> enrolled = enroll("pavel");
    String access = enrolled.get(0);
    String proof = enrolled.get(2);
    String anotherOldCode = enrolled.get(3);

    List<String> fresh = regenerate(access, PASSWORD, proof, 200);

    assertThat(fresh).hasSize(TotpService.RECOVERY_CODE_COUNT);
    assertThat(count(access)).isEqualTo(10);
    // The proof code and every other old code are dead; a fresh one works.
    authenticate(loginChallenge("pavel"), proof, 401);
    authenticate(loginChallenge("pavel"), anotherOldCode, 401);
    authenticate(loginChallenge("pavel"), fresh.get(0), 200);
    assertThat(count(access)).isEqualTo(9);
  }

  @Test
  void wrongPasswordGetsTheIdentical401AndTheOldSetSurvives() throws Exception {
    List<String> enrolled = enroll("quinn");
    String access = enrolled.get(0);

    mockMvc.perform(post("/api/auth/2fa/recovery-codes/regenerate")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"wr0ng-pass\",\"code\":\""
                + currentCode(enrolled.get(1)) + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("invalid credentials"));

    assertThat(count(access)).isEqualTo(10);
    authenticate(loginChallenge("quinn"), enrolled.get(2), 200);
    assertThat(auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(
        AuditTargetType.USER, userId("quinn")).stream()
        .filter(r -> r.getAction() == AuditAction.RECOVERY_CODES_REGENERATED)).isEmpty();
  }

  @Test
  void wrongCodeGetsTheIdentical401AndTheOldSetSurvives() throws Exception {
    List<String> enrolled = enroll("ruth");
    String access = enrolled.get(0);

    mockMvc.perform(post("/api/auth/2fa/recovery-codes/regenerate")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\",\"code\":\""
                + wrongCode(enrolled.get(1)) + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("invalid credentials"));

    assertThat(count(access)).isEqualTo(10);
    authenticate(loginChallenge("ruth"), enrolled.get(2), 200);
  }

  @Test
  void wrongPasswordAttemptNeverConsumesThePresentedRecoveryCode() throws Exception {
    List<String> enrolled = enroll("stas");
    String access = enrolled.get(0);
    String recoveryCode = enrolled.get(2);

    // Password checked before the second factor is touched: the failed
    // attempt must not burn the one-time code it carried.
    regenerate(access, "wr0ng-pass", recoveryCode, 401);
    List<String> fresh = regenerate(access, PASSWORD, recoveryCode, 200);
    assertThat(fresh).hasSize(TotpService.RECOVERY_CODE_COUNT);
    assertThat(count(access)).isEqualTo(10);
  }

  @Test
  void regenerateWhenNotEnabledIs422() throws Exception {
    String access = register("tara"); // never enrolled in 2FA
    regenerate(access, PASSWORD, "123456", 422);
  }

  @Test
  void regenerateRequiresAuthentication() throws Exception {
    mockMvc.perform(post("/api/auth/2fa/recovery-codes/regenerate")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\",\"code\":\"123456\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }
}
