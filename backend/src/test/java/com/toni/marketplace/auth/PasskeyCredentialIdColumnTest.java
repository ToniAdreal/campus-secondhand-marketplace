package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * DB-level pin for the {@code passkey_credential.credential_id} column
 * shape (backlog #372): the column is VARCHAR(1024) because credential
 * ids are authenticator-defined and can run long (the webauthn4j test
 * authenticator emits ~270 chars), and the MySQL variant of Flyway V64
 * pins it to CHARACTER SET ascii because a utf8mb4 UNIQUE key on 1024
 * chars is 4096 bytes — past MySQL's 3072-byte limit, which failed V64
 * on every CI compose run ("Specified key was too long", run
 * 42842365028). H2 has no such limit, so this suite cannot reproduce
 * the MySQL failure; it pins the behaviour the ascii charset exists
 * to protect — a full-width id stores and reads back, and the unique
 * constraint still rejects a duplicate.
 */
@DataJpaTest
class PasskeyCredentialIdColumnTest {

  @Autowired
  private PasskeyCredentialRepository credentials;

  @Autowired
  private UserRepository users;

  private Long newUserId(String name) {
    return users.saveAndFlush(
        new User(name, name + "@example.com", "$2a$12$hashed")).getId();
  }

  private PasskeyCredential credential(long userId, String credentialId) {
    return new PasskeyCredential(userId, credentialId, new byte[] {1, 2, 3},
        0L, new byte[16]);
  }

  @Test
  void fullWidthCredentialIdStoresAndReadsBack() {
    Long userId = newUserId("credentialidwidthuser");
    String id = "A".repeat(1024);

    PasskeyCredential saved = credentials.saveAndFlush(credential(userId, id));

    assertThat(credentials.findByCredentialId(id))
        .hasValueSatisfying(found -> {
          assertThat(found.getId()).isEqualTo(saved.getId());
          assertThat(found.getCredentialId()).isEqualTo(id);
        });
  }

  @Test
  void webauthnLengthCredentialIdStores() {
    Long userId = newUserId("credentialid270user");
    String id = "B".repeat(270);

    PasskeyCredential saved = credentials.saveAndFlush(credential(userId, id));

    assertThat(credentials.findByUserIdAndCredentialId(userId, id))
        .hasValueSatisfying(found -> assertThat(found.getId()).isEqualTo(saved.getId()));
  }

  @Test
  void duplicateCredentialIdIsRejected() {
    Long userId = newUserId("credentialiddupuser");
    String id = "C".repeat(270);
    credentials.saveAndFlush(credential(userId, id));

    assertThatThrownBy(() -> credentials.saveAndFlush(credential(userId, id)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
