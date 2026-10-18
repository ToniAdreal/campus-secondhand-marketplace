package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditLogRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Step-up confirmation for the account data export (backlog #188):
 * {@code POST /api/users/me/export/confirm} verifies a fresh proof
 * (current password, plus a TOTP code when 2FA is enabled) and mints
 * a short-lived single-use grant; {@code GET /api/users/me/export}
 * consumes it via the {@code X-Export-Grant} header.
 *
 * <p>Pinned here: the grant matrix — happy path, export without a
 * grant 401, wrong password 401, TOTP account without/with a wrong
 * code 401 (identical envelope, no oracle), expired grant 401, grant
 * reuse 401, another account's grant 401 — and the audit rows for
 * both steps (grant issued + exported). The export payload itself is
 * pinned by {@code AccountDataExportTest} (its denylist unchanged).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DataExportGrantTest {

  private static final String PASSWORD = "export-grant-password-1";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;
  @Autowired private TotpService totp;
  @Autowired private DataExportGrantRepository grants;
  @Autowired private AuditLogRepository auditLog;
  @Autowired private ObjectMapper objectMapper;

  private record Account(User user, String token) {}

  private Account freshAccount() {
    String tag = "grant-" + UUID.randomUUID().toString().substring(0, 8);
    User user = users.save(new User(tag, tag + "@example.com", passwords.encode(PASSWORD)));
    return new Account(user, jwt.createTokenPair(user).accessToken());
  }

  /** Enables TOTP on the account through the real API; returns the secret. */
  private String enableTotp(Account account) throws Exception {
    MvcResult setup = mockMvc.perform(post("/api/auth/2fa/setup")
            .header("Authorization", "Bearer " + account.token()))
        .andExpect(status().isOk())
        .andReturn();
    String secret = JsonPath.read(setup.getResponse().getContentAsString(), "$.data.secret");
    mockMvc.perform(post("/api/auth/2fa/enable")
            .header("Authorization", "Bearer " + account.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + totp.codeAt(secret, Instant.now()) + "\"}"))
        .andExpect(status().isOk());
    return secret;
  }

  private MvcResult confirm(Account account, String body, int expected) throws Exception {
    return mockMvc.perform(post("/api/users/me/export/confirm")
            .header("Authorization", "Bearer " + account.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().is(expected))
        .andReturn();
  }

  private String confirmOk(Account account, String body) throws Exception {
    MvcResult result = confirm(account, body, 200);
    return objectMapper.readTree(result.getResponse().getContentAsString())
        .path("data").path("grant").asText();
  }

  private void exportWith(Account account, String grant, int expected) throws Exception {
    var request = get("/api/users/me/export")
        .header("Authorization", "Bearer " + account.token());
    if (grant != null) {
      request = request.header(AccountDataExportController.EXPORT_GRANT_HEADER, grant);
    }
    mockMvc.perform(request).andExpect(status().is(expected));
  }

  @Test
  void happyPathConfirmThenExportWritesBothAuditRows() throws Exception {
    Account account = freshAccount();
    long grantsBefore = auditLog.countByAction(AuditAction.DATA_EXPORT_GRANT_ISSUED);
    long exportsBefore = auditLog.countByAction(AuditAction.DATA_EXPORTED);

    String grant = confirmOk(account, "{\"currentPassword\":\"" + PASSWORD + "\"}");
    assertThat(grant).isNotBlank();
    exportWith(account, grant, 200);

    assertThat(auditLog.countByAction(AuditAction.DATA_EXPORT_GRANT_ISSUED))
        .isEqualTo(grantsBefore + 1);
    assertThat(auditLog.countByAction(AuditAction.DATA_EXPORTED))
        .isEqualTo(exportsBefore + 1);
  }

  @Test
  void exportWithoutGrantIs401() throws Exception {
    Account account = freshAccount();
    exportWith(account, null, 401);
    exportWith(account, "not-a-real-grant", 401);
  }

  @Test
  void wrongPasswordIs401AndMintsNoGrant() throws Exception {
    Account account = freshAccount();
    confirm(account, "{\"currentPassword\":\"wr0ng-password\"}", 401);
    assertThat(grants.findAll().stream()
        .filter(g -> g.getUserId().equals(account.user().getId()))).isEmpty();
  }

  @Test
  void totpAccountNeedsCodeAndWrongCodeIsTheIdentical401() throws Exception {
    Account account = freshAccount();
    String secret = enableTotp(account);

    // No code: 401. Wrong code: the identical 401 envelope.
    MvcResult noCode = confirm(account, "{\"currentPassword\":\"" + PASSWORD + "\"}", 401);
    MvcResult wrongCode = confirm(account,
        "{\"currentPassword\":\"" + PASSWORD + "\",\"code\":\"000000\"}", 401);
    assertThat(noCode.getResponse().getContentAsString())
        .isEqualTo(wrongCode.getResponse().getContentAsString());

    // The valid code mints a grant that exports.
    String grant = confirmOk(account,
        "{\"currentPassword\":\"" + PASSWORD + "\",\"code\":\""
            + totp.codeAt(secret, Instant.now()) + "\"}");
    exportWith(account, grant, 200);
  }

  @Test
  void expiredGrantIs401() throws Exception {
    Account account = freshAccount();
    grants.save(new DataExportGrant(account.user().getId(),
        JwtTokenService.sha256Hex("expired-raw-grant"),
        Instant.now().minusSeconds(60)));
    exportWith(account, "expired-raw-grant", 401);
  }

  @Test
  void grantIsSingleUseAndBoundToItsAccount() throws Exception {
    Account account = freshAccount();
    Account other = freshAccount();
    String grant = confirmOk(account, "{\"currentPassword\":\"" + PASSWORD + "\"}");

    // Another account cannot spend it …
    exportWith(other, grant, 401);
    // … the owner can, exactly once …
    exportWith(account, grant, 200);
    // … and a replay is the identical 401.
    exportWith(account, grant, 401);
  }

  @Test
  void anonymousConfirmIs401Envelope() throws Exception {
    mockMvc.perform(post("/api/users/me/export/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }
}
