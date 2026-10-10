package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.audit.AuditTargetType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Backlog #127 — the regeneration joins its caller's transaction,
 * mirroring {@code TotpDisableRollbackTest}: a regeneration executed
 * inside a transaction that is then rolled back leaves the OLD
 * recovery-code set fully valid and writes no
 * {@code RECOVERY_CODES_REGENERATED} audit row. The trail can never
 * claim a fresh set was issued when the old one is still the live one.
 */
@SpringBootTest
class TotpRecoveryCodesRegenerateRollbackTest {

  private static final String PASSWORD = "s3cret-pass";

  @Autowired
  private AuthService auth;

  @Autowired
  private AuditLogRepository auditLog;

  @Autowired
  private UserRepository users;

  @Autowired
  private TotpRecoveryCodeRepository recoveryCodes;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private TotpService totp;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @AfterEach
  void cleanUp() {
    auditLog.deleteAll();
    recoveryCodes.deleteAll();
    users.deleteAll();
  }

  /** Creates + enrolls a user in committed transactions; returns [userId, secret, codes...]. */
  private List<Object> enrollCommitted(String username) {
    User user = users.save(new User(username, username + "@example.com",
        passwords.encode(PASSWORD)));
    long userId = user.getId();
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    String[] secret = new String[1];
    tx.executeWithoutResult(status -> secret[0] = auth.setupTotp(userId).secret());
    List<String> codes = tx.execute(status ->
        auth.enableTotp(userId, totp.codeAt(secret[0], Instant.now())));
    java.util.List<Object> out = new java.util.ArrayList<>();
    out.add(userId);
    out.add(secret[0]);
    out.addAll(codes);
    return out;
  }

  private List<String> hashesOf(long userId) {
    return recoveryCodes.findAll().stream()
        .filter(r -> r.getUserId().equals(userId))
        .map(TotpRecoveryCode::getCodeHash)
        .toList();
  }

  @Test
  void rolledBackRegenerateKeepsTheOldSetAndNoAuditRow() {
    List<Object> enrolled = enrollCommitted("regen-rollback");
    long userId = (Long) enrolled.get(0);
    String secret = (String) enrolled.get(1);
    List<String> hashesBefore = hashesOf(userId);
    assertThat(hashesBefore).hasSize(TotpService.RECOVERY_CODE_COUNT);

    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.executeWithoutResult(status -> {
      auth.regenerateRecoveryCodes(userId, PASSWORD, totp.codeAt(secret, Instant.now()));
      status.setRollbackOnly();
    });

    // The old set is exactly the live set again — same hashes, all unused.
    assertThat(hashesOf(userId)).containsExactlyInAnyOrderElementsOf(hashesBefore);
    assertThat(auth.recoveryCodeCount(userId)).isEqualTo(TotpService.RECOVERY_CODE_COUNT);
    assertThat(auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(
        AuditTargetType.USER, userId).stream()
        .filter(r -> r.getAction() == AuditAction.RECOVERY_CODES_REGENERATED)).isEmpty();
  }

  @Test
  void committedRegenerateReplacesTheSetAndLeavesExactlyOneAuditRow() {
    List<Object> enrolled = enrollCommitted("regen-commit");
    long userId = (Long) enrolled.get(0);
    String secret = (String) enrolled.get(1);
    List<String> hashesBefore = hashesOf(userId);

    List<String> fresh = auth.regenerateRecoveryCodes(
        userId, PASSWORD, totp.codeAt(secret, Instant.now()));

    assertThat(fresh).hasSize(TotpService.RECOVERY_CODE_COUNT);
    List<String> hashesAfter = hashesOf(userId);
    assertThat(hashesAfter).hasSize(TotpService.RECOVERY_CODE_COUNT)
        .doesNotContainAnyElementsOf(hashesBefore)
        .containsExactlyInAnyOrderElementsOf(fresh.stream()
            .map(c -> JwtTokenService.sha256Hex(TotpService.normalizeRecoveryCode(c)))
            .toList());
    assertThat(auth.recoveryCodeCount(userId)).isEqualTo(TotpService.RECOVERY_CODE_COUNT);

    var rows = auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(AuditTargetType.USER, userId);
    assertThat(rows.stream()
        .filter(r -> r.getAction() == AuditAction.RECOVERY_CODES_REGENERATED))
        .hasSize(1);
    assertThat(rows.stream()
        .filter(r -> r.getAction() == AuditAction.RECOVERY_CODES_REGENERATED)
        .findFirst().orElseThrow().getActorUserId()).isEqualTo(userId);
    // The enrollment itself is untouched: 2FA still on, same secret.
    User after = users.findById(userId).orElseThrow();
    assertThat(after.isTotpEnabled()).isTrue();
  }
}
