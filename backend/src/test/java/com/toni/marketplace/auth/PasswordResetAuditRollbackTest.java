package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.audit.AuditTargetType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Backlog #110 — the reset-confirm audit write joins the confirm's own
 * transaction (the #98 property, extended to the reset path): a confirm
 * executed inside a transaction that is then rolled back leaves no audit
 * row and no password change; a committed confirm leaves exactly one
 * {@code PASSWORD_RESET_COMPLETED} row. The token row is seeded directly
 * (hash of a known raw token) so no mail capture is needed.
 */
@SpringBootTest
class PasswordResetAuditRollbackTest {

  private static final String RAW_TOKEN = "rollback-test-raw-token";

  @Autowired
  private PasswordResetService resets;

  @Autowired
  private AuditLogRepository auditLog;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordResetTokenRepository resetTokens;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @AfterEach
  void cleanUp() {
    auditLog.deleteAll();
    resetTokens.deleteAll();
    refreshTokens.deleteAll();
    users.deleteAll();
  }

  private User seedUserWithLiveToken(String username) {
    User user = users.save(new User(username, username + "@example.com",
        passwords.encode("s3cret-pass1")));
    resetTokens.save(new PasswordResetToken(user.getId(),
        JwtTokenService.sha256Hex(RAW_TOKEN), Instant.now().plus(30, ChronoUnit.MINUTES)));
    return user;
  }

  @Test
  void rolledBackConfirmLeavesNoAuditRow() {
    User user = seedUserWithLiveToken("reset-rollback");
    long userId = user.getId();
    String hashBefore = user.getPasswordHash();

    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.executeWithoutResult(status -> {
      resets.confirmReset(RAW_TOKEN, "n3w-s3cret12");
      status.setRollbackOnly();
    });

    // Neither the audit row nor the password change survived the rollback.
    assertThat(auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(
        AuditTargetType.USER, userId)).isEmpty();
    assertThat(users.findById(userId).orElseThrow().getPasswordHash()).isEqualTo(hashBefore);
  }

  @Test
  void committedConfirmLeavesExactlyOneAuditRow() {
    User user = seedUserWithLiveToken("reset-commit");
    long userId = user.getId();

    resets.confirmReset(RAW_TOKEN, "n3w-s3cret12");

    var rows = auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(AuditTargetType.USER, userId);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getAction()).isEqualTo(AuditAction.PASSWORD_RESET_COMPLETED);
    assertThat(rows.get(0).getActorUserId()).isEqualTo(userId);
  }
}
