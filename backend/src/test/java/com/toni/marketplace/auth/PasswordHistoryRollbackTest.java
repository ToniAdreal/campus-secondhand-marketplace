package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Backlog #139 — the history write joins the credential change's own
 * transaction (the #98/#110 property, extended to password history): a
 * change executed inside a transaction that is then rolled back leaves
 * no history row and no password change; a committed change leaves
 * exactly one row, holding the OUTGOING hash.
 */
@SpringBootTest
class PasswordHistoryRollbackTest {

  @Autowired
  private AuthService auth;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordHistoryRepository historyRepo;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @AfterEach
  void cleanUp() {
    historyRepo.deleteAll();
    refreshTokens.deleteAll();
    users.deleteAll();
  }

  @Test
  void rolledBackChangeLeavesNoHistoryRow() {
    User user = users.save(new User("history-rollback", "history-rollback@example.com",
        passwords.encode("s3cret-pass1")));
    long userId = user.getId();
    String hashBefore = user.getPasswordHash();

    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.executeWithoutResult(status -> {
      auth.changePassword(userId, "s3cret-pass1", "n3w-s3cret12");
      status.setRollbackOnly();
    });

    assertThat(historyRepo.countByUserId(userId)).isZero();
    assertThat(users.findById(userId).orElseThrow().getPasswordHash()).isEqualTo(hashBefore);
  }

  @Test
  void committedChangeLeavesExactlyOneOutgoingRow() {
    User user = users.save(new User("history-commit", "history-commit@example.com",
        passwords.encode("s3cret-pass1")));
    long userId = user.getId();

    auth.changePassword(userId, "s3cret-pass1", "n3w-s3cret12");

    var rows = historyRepo.findByUserIdOrderByIdDesc(userId,
        org.springframework.data.domain.PageRequest.of(0, 5));
    assertThat(rows).hasSize(1);
    // The row holds the outgoing password's hash — it matches the OLD
    // password, never the new one, and is a BCrypt hash, not plaintext.
    assertThat(passwords.matches("s3cret-pass1", rows.get(0).getPasswordHash())).isTrue();
    assertThat(passwords.matches("n3w-s3cret12", rows.get(0).getPasswordHash())).isFalse();
    assertThat(rows.get(0).getPasswordHash()).doesNotContain("s3cret");
  }
}
