package com.toni.marketplace.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.toni.marketplace.auth.AuthService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.RefreshTokenRepository;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Backlog #98 — the audit write joins the caller's transaction: a password
 * change executed inside a transaction that is then rolled back leaves no
 * audit row (and no password change). This is the property that makes the
 * trail trustworthy — it can never claim a transition happened when the
 * transition itself did not commit.
 */
@SpringBootTest
class AuditRollbackTest {

  @Autowired
  private AuthService auth;

  @Autowired
  private AuditLogRepository auditLog;

  @Autowired
  private UserRepository users;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @AfterEach
  void cleanUp() {
    auditLog.deleteAll();
    refreshTokens.deleteAll();
    users.deleteAll();
  }

  @Test
  void rolledBackPasswordChangeLeavesNoAuditRow() {
    User user = users.save(new User("audit-rollback", "audit-rollback@example.com",
        passwords.encode("s3cret-pass")));
    long userId = user.getId();
    String hashBefore = user.getPasswordHash();

    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.executeWithoutResult(status -> {
      auth.changePassword(userId, "s3cret-pass", "n3w-s3cret");
      status.setRollbackOnly();
    });

    // Neither the audit row nor the password change survived the rollback.
    assertThat(auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(
        AuditTargetType.USER, userId)).isEmpty();
    assertThat(users.findById(userId).orElseThrow().getPasswordHash()).isEqualTo(hashBefore);
  }

  @Test
  void committedPasswordChangeLeavesExactlyOneAuditRow() {
    User user = users.save(new User("audit-commit", "audit-commit@example.com",
        passwords.encode("s3cret-pass")));
    long userId = user.getId();

    auth.changePassword(userId, "s3cret-pass", "n3w-s3cret");

    var rows = auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(AuditTargetType.USER, userId);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getAction()).isEqualTo(AuditAction.PASSWORD_CHANGED);
    assertThat(rows.get(0).getActorUserId()).isEqualTo(userId);
  }
}
