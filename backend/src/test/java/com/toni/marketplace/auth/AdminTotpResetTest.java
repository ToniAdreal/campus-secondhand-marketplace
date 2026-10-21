package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditLog;
import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.audit.AuditTargetType;
import com.toni.marketplace.notification.Notification;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.notification.NotificationType;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN-assisted TOTP reset (backlog #209): POST
 * /api/admin/users/{id}/totp-reset is the supported recovery path for an
 * account that lost its TOTP device AND its recovery codes. It clears
 * the secret, deletes the recovery-code set, retires every trusted
 * device, revokes every session (refresh families deleted, tokenVersion
 * bumped so live Bearer tokens die), clears the TOTP lockout, and
 * appends a {@code TOTP_RESET_BY_ADMIN} audit row plus a same-named
 * notification for the holder. Guards: an ADMIN target is 422 (staff
 * recovery must not strip another operator's factor — the actor's own
 * admin account included), a non-TOTP account is 422, unknown id 404,
 * non-admin callers 403, anonymous 401 — and a refused reset changes
 * nothing and writes neither row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users and tokens it creates
class AdminTotpResetTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Autowired
  private TotpRecoveryCodeRepository recoveryCodes;

  @Autowired
  private TrustedDeviceRepository trustedDevices;

  @Autowired
  private AuditLogRepository auditLog;

  @Autowired
  private NotificationRepository notifications;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  /** Distinct client IP per login call, so the per-IP login rate-limit
   *  bucket (5/min) never throttles this suite's legitimate logins. */
  private final AtomicInteger ipSequence = new AtomicInteger();

  private User admin;
  private User secondAdmin;
  private User victim;
  private User plainUser;
  private String adminToken;
  private String victimToken;

  @BeforeEach
  void createUsersAndTokens() {
    admin = new User("rst-admin", "rst-admin@example.com", passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    admin = users.save(admin);
    // The acting admin's OWN factor: the self-reset test pins that even
    // this account cannot be reset through the endpoint.
    admin.setTotpSecret("rst-admin-secret");
    admin.setTotpEnabled(true);
    admin = users.save(admin);

    secondAdmin = new User("rst-admin2", "rst-admin2@example.com",
        passwords.encode("admin-password"));
    secondAdmin.setRoles(EnumSet.of(Role.ADMIN));
    secondAdmin.setTotpSecret("rst-admin2-secret");
    secondAdmin.setTotpEnabled(true);
    secondAdmin = users.save(secondAdmin);

    // The locked-out holder: TOTP on, mid-lockout, with live artefacts
    // of every kind the reset must clear.
    victim = new User("rst-victim", "rst-victim@example.com",
        passwords.encode("victim-password"));
    victim.setTotpSecret("rst-victim-secret");
    victim.setTotpEnabled(true);
    victim.setFailedTotpAttempts(5);
    victim.setTotpLockedUntil(Instant.now().plusSeconds(900));
    victim = users.save(victim);
    recoveryCodes.save(new TotpRecoveryCode(victim.getId(),
        JwtTokenService.sha256Hex("RSTCODE1")));
    recoveryCodes.save(new TotpRecoveryCode(victim.getId(),
        JwtTokenService.sha256Hex("RSTCODE2")));
    trustedDevices.save(new TrustedDevice(victim.getId(),
        JwtTokenService.sha256Hex("rst-trusted-token"), "test-agent", "10.209.0.1",
        Instant.now().plusSeconds(86400)));

    plainUser = users.save(new User("rst-plain", "rst-plain@example.com",
        passwords.encode("plain-password")));

    adminToken = jwt.createTokenPair(admin).accessToken();
    victimToken = jwt.createTokenPair(victim).accessToken();
  }

  private MockHttpServletRequestBuilder loginRequest(String username, String password) {
    String ip = "10.209.0." + ipSequence.incrementAndGet();
    return post("/api/auth/login")
        .with(request -> {
          request.setRemoteAddr(ip);
          return request;
        })
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"usernameOrEmail\":\"" + username + "\",\"password\":\"" + password + "\"}");
  }

  private List<AuditLog> auditRowsFor(long targetId) {
    return auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(AuditTargetType.USER, targetId);
  }

  private List<Notification> notificationsFor(long userId) {
    return notifications.findAll().stream()
        .filter(n -> n.getRecipientId().equals(userId))
        .toList();
  }

  @Test
  void adminResetClearsSecretCodesDevicesSessionsAndLockout() throws Exception {
    // Pre-state: every artefact exists.
    assertThat(recoveryCodes.countByUserIdAndUsedAtIsNull(victim.getId())).isEqualTo(2);
    assertThat(trustedDevices.findByUserIdOrderByCreatedAtAsc(victim.getId())).hasSize(1);
    assertThat(refreshTokens.findAll().stream()
        .filter(row -> row.getUserId().equals(victim.getId())).toList()).isNotEmpty();
    mockMvc.perform(get("/api/auth/me")
            .header("Authorization", "Bearer " + victimToken))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/admin/users/{id}/totp-reset", victim.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.id").value(victim.getId()))
        .andExpect(jsonPath("$.data.totpEnabled").value(false));

    User after = users.findById(victim.getId()).orElseThrow();
    assertThat(after.isTotpEnabled()).isFalse();
    assertThat(after.getTotpSecret()).isNull();
    assertThat(after.getFailedTotpAttempts()).isZero();
    assertThat(after.getTotpLockedUntil()).isNull();
    assertThat(recoveryCodes.countByUserIdAndUsedAtIsNull(victim.getId())).isZero();
    assertThat(recoveryCodes.findAll().stream()
        .filter(row -> row.getUserId().equals(victim.getId())).toList()).isEmpty();
    assertThat(trustedDevices.findByUserIdOrderByCreatedAtAsc(victim.getId())).isEmpty();
    assertThat(refreshTokens.findAll().stream()
        .filter(row -> row.getUserId().equals(victim.getId())).toList()).isEmpty();

    // The pre-reset Bearer token is dead (tokenVersion bump), and the
    // holder signs in with the password ALONE — no 202 challenge, even
    // though the account was TOTP-locked when the reset landed.
    mockMvc.perform(get("/api/auth/me")
            .header("Authorization", "Bearer " + victimToken))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(loginRequest("rst-victim", "victim-password"))
        .andExpect(status().isOk());
  }

  @Test
  void resetWritesAuditRowAndHolderNotification() throws Exception {
    mockMvc.perform(post("/api/admin/users/{id}/totp-reset", victim.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());

    List<AuditLog> rows = auditRowsFor(victim.getId());
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getAction()).isEqualTo(AuditAction.TOTP_RESET_BY_ADMIN);
    assertThat(rows.get(0).getActorUserId()).isEqualTo(admin.getId());

    List<Notification> notes = notificationsFor(victim.getId());
    assertThat(notes).hasSize(1);
    assertThat(notes.get(0).getType()).isEqualTo(NotificationType.TOTP_RESET_BY_ADMIN);
    // The acting admin is not notified about their own action.
    assertThat(notificationsFor(admin.getId())).isEmpty();
  }

  @Test
  void resettingAnotherAdminIs422AndChangesNothing() throws Exception {
    mockMvc.perform(post("/api/admin/users/{id}/totp-reset", secondAdmin.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422))
        .andExpect(jsonPath("$.message")
            .value("cannot reset two-factor authentication for an admin user"));

    User after = users.findById(secondAdmin.getId()).orElseThrow();
    assertThat(after.isTotpEnabled()).isTrue();
    assertThat(after.getTotpSecret()).isEqualTo("rst-admin2-secret");
    assertThat(auditRowsFor(secondAdmin.getId())).isEmpty();
    assertThat(notificationsFor(secondAdmin.getId())).isEmpty();
  }

  @Test
  void resettingOwnAdminAccountIs422Too() throws Exception {
    // The ADMIN-target guard covers the actor themselves: a lone
    // admin's own reset stays a database runbook case (README), never
    // an endpoint call.
    mockMvc.perform(post("/api/admin/users/{id}/totp-reset", admin.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));

    assertThat(users.findById(admin.getId()).orElseThrow().isTotpEnabled()).isTrue();
    assertThat(auditRowsFor(admin.getId())).isEmpty();
  }

  @Test
  void resettingAccountWithoutTotpIs422() throws Exception {
    mockMvc.perform(post("/api/admin/users/{id}/totp-reset", plainUser.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422))
        .andExpect(jsonPath("$.message").value("two-factor authentication is not enabled"));

    assertThat(auditRowsFor(plainUser.getId())).isEmpty();
    assertThat(notificationsFor(plainUser.getId())).isEmpty();
  }

  @Test
  void resetUnknownUserIs404() throws Exception {
    mockMvc.perform(post("/api/admin/users/{id}/totp-reset", 999999L)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("user not found"));
  }

  @Test
  void nonAdminResetIsForbidden() throws Exception {
    mockMvc.perform(post("/api/admin/users/{id}/totp-reset", victim.getId())
            .header("Authorization", "Bearer " + victimToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    assertThat(users.findById(victim.getId()).orElseThrow().isTotpEnabled()).isTrue();
  }

  @Test
  void anonymousResetIsUnauthorized() throws Exception {
    mockMvc.perform(post("/api/admin/users/{id}/totp-reset", victim.getId()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    assertThat(users.findById(victim.getId()).orElseThrow().isTotpEnabled()).isTrue();
  }
}
