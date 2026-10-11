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
 * Backlog #121 — turning TOTP 2FA off, end-to-end via MockMvc: the
 * disable demands the current password AND a second factor (TOTP code
 * or an unused recovery code), clears the secret, deletes the recovery
 * set, writes a {@code TOTP_DISABLED} audit row, and lets the account
 * log in with the password alone afterwards (200, not the 202
 * challenge). Wrong proofs get the identical 401; disabling when 2FA is
 * off is 422; the caller's session survives.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users, codes and audit rows it creates
class TotpDisableTest {

  private static final AtomicInteger IP_SEQ = new AtomicInteger();
  private static final String PASSWORD = "s3cret-pass1";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private TotpRecoveryCodeRepository recoveryCodes;

  @Autowired
  private AuditLogRepository auditLog;

  @Autowired
  private TotpService totp;

  /** Distinct client IPs per call keep the per-IP login rate-limit bucket out of the way. */
  private static RequestPostProcessor uniqueIp() {
    return request -> {
      request.setRemoteAddr("10.121.0." + IP_SEQ.incrementAndGet());
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

  private void disable(String accessToken, String password, String code, int expected)
      throws Exception {
    mockMvc.perform(post("/api/auth/2fa/disable")
            .header("Authorization", "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + password + "\",\"code\":\"" + code + "\"}"))
        .andExpect(status().is(expected));
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
  void disableWithTotpCodeTurns2faOffAndLoginAnswers200Not202() throws Exception {
    List<String> enrolled = enroll("ivan");
    String access = enrolled.get(0);
    String secret = enrolled.get(1);

    disable(access, PASSWORD, currentCode(secret), 200);

    User user = users.findById(userId("ivan")).orElseThrow();
    assertThat(user.isTotpEnabled()).isFalse();
    assertThat(user.getTotpSecret()).isNull();
    assertThat(count(access)).isEqualTo(0);
    assertThat(recoveryCodes.findAll().stream()
        .filter(r -> r.getUserId().equals(user.getId()))).isEmpty();

    // The session survives the disable: the pre-disable access token
    // still authenticates (no tokenVersion bump, no revocation).
    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("ivan"));

    // Password login now finishes in one step — 200 with a token pair,
    // not the 202 second-factor challenge.
    mockMvc.perform(post("/api/auth/login")
            .with(uniqueIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"ivan\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accessToken").exists());

    // Exactly one TOTP_DISABLED audit row, actor = target = the holder.
    var rows = auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(
        AuditTargetType.USER, user.getId()).stream()
        .filter(r -> r.getAction() == AuditAction.TOTP_DISABLED)
        .toList();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getActorUserId()).isEqualTo(user.getId());
  }

  @Test
  void disableWithRecoveryCodeConsumesTheSet() throws Exception {
    List<String> enrolled = enroll("judy");
    String access = enrolled.get(0);
    String recoveryCode = enrolled.get(2);

    disable(access, PASSWORD, recoveryCode, 200);

    assertThat(users.findById(userId("judy")).orElseThrow().isTotpEnabled()).isFalse();
    // The used code — and the whole set with it — is gone.
    assertThat(count(access)).isEqualTo(0);
    // A second disable is now a state conflict, not another success.
    disable(access, PASSWORD, enrolled.get(3), 422);
  }

  @Test
  void wrongPasswordGetsTheIdentical401And2faStaysOn() throws Exception {
    List<String> enrolled = enroll("karl");
    String access = enrolled.get(0);

    mockMvc.perform(post("/api/auth/2fa/disable")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"wr0ng-pass12\",\"code\":\""
                + currentCode(enrolled.get(1)) + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("invalid credentials"));

    // Nothing moved: 2FA on, the set intact, login still challenges.
    assertThat(users.findById(userId("karl")).orElseThrow().isTotpEnabled()).isTrue();
    assertThat(count(access)).isEqualTo(10);
    mockMvc.perform(post("/api/auth/login")
            .with(uniqueIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"karl\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isAccepted());
  }

  @Test
  void wrongCodeGetsTheIdentical401AndDoesNotBurnARecoveryCode() throws Exception {
    List<String> enrolled = enroll("lena");
    String access = enrolled.get(0);

    mockMvc.perform(post("/api/auth/2fa/disable")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\",\"code\":\""
                + wrongCode(enrolled.get(1)) + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("invalid credentials"));

    assertThat(users.findById(userId("lena")).orElseThrow().isTotpEnabled()).isTrue();
    assertThat(count(access)).isEqualTo(10);
  }

  @Test
  void wrongPasswordAttemptNeverConsumesThePresentedRecoveryCode() throws Exception {
    List<String> enrolled = enroll("mona");
    String access = enrolled.get(0);
    String recoveryCode = enrolled.get(2);

    // Password checked before the second factor is touched: the failed
    // attempt must not burn the one-time code it carried.
    disable(access, "wr0ng-pass12", recoveryCode, 401);
    disable(access, PASSWORD, recoveryCode, 200);
    assertThat(users.findById(userId("mona")).orElseThrow().isTotpEnabled()).isFalse();
  }

  @Test
  void disableWhenNotEnabledIs422() throws Exception {
    String access = register("nina"); // never enrolled in 2FA
    disable(access, PASSWORD, "123456", 422);
  }

  @Test
  void disableRequiresAuthentication() throws Exception {
    mockMvc.perform(post("/api/auth/2fa/disable")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\",\"code\":\"123456\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }
}
