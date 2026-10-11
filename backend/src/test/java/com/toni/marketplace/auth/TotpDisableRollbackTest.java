package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.audit.AuditTargetType;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Backlog #121 — the disable joins its caller's transaction, mirroring
 * {@code AuditRollbackTest}: a disable executed inside a transaction
 * that is then rolled back leaves 2FA fully on — secret, recovery set
 * and enabled flag untouched — and writes no {@code TOTP_DISABLED}
 * audit row. The trail can never claim 2FA was turned off when it was
 * not.
 */
@SpringBootTest
class TotpDisableRollbackTest {

  private static final String PASSWORD = "s3cret-pass1";

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

  @Test
  void rolledBackDisableLeaves2faOnAndNoAuditRow() {
    User user = users.save(new User("totp-rollback", "totp-rollback@example.com",
        passwords.encode(PASSWORD)));
    long userId = user.getId();

    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    // Enroll in a committed transaction: setup mints the secret, enable
    // verifies a current code against it and issues the recovery set.
    String[] secret = new String[1];
    tx.executeWithoutResult(status -> secret[0] = auth.setupTotp(userId).secret());
    tx.executeWithoutResult(status ->
        auth.enableTotp(userId, totp.codeAt(secret[0], Instant.now())));
    assertThat(users.findById(userId).orElseThrow().isTotpEnabled()).isTrue();
    String storedSecret = users.findById(userId).orElseThrow().getTotpSecret();

    tx.executeWithoutResult(status -> {
      auth.disableTotp(userId, PASSWORD, totp.codeAt(secret[0], Instant.now()));
      status.setRollbackOnly();
    });

    // Neither the disable nor its audit row survived the rollback.
    User after = users.findById(userId).orElseThrow();
    assertThat(after.isTotpEnabled()).isTrue();
    assertThat(after.getTotpSecret()).isEqualTo(storedSecret);
    assertThat(auth.recoveryCodeCount(userId)).isEqualTo(TotpService.RECOVERY_CODE_COUNT);
    assertThat(auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(
        AuditTargetType.USER, userId).stream()
        .filter(r -> r.getAction() == AuditAction.TOTP_DISABLED)).isEmpty();
  }

  @Test
  void committedDisableLeavesExactlyOneAuditRow() {
    User user = users.save(new User("totp-commit", "totp-commit@example.com",
        passwords.encode(PASSWORD)));
    long userId = user.getId();

    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    String[] secret = new String[1];
    tx.executeWithoutResult(status -> secret[0] = auth.setupTotp(userId).secret());
    tx.executeWithoutResult(status ->
        auth.enableTotp(userId, totp.codeAt(secret[0], Instant.now())));

    auth.disableTotp(userId, PASSWORD, totp.codeAt(secret[0], Instant.now()));

    var rows = auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(AuditTargetType.USER, userId);
    assertThat(rows.stream().filter(r -> r.getAction() == AuditAction.TOTP_DISABLED))
        .hasSize(1);
    assertThat(rows.stream()
        .filter(r -> r.getAction() == AuditAction.TOTP_DISABLED)
        .findFirst().orElseThrow().getActorUserId()).isEqualTo(userId);
    assertThat(users.findById(userId).orElseThrow().isTotpEnabled()).isFalse();
  }
}
