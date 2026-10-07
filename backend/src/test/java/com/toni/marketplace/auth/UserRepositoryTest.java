package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class UserRepositoryTest {

  @Autowired
  private UserRepository users;

  @Test
  void persistsAndFindsByUsername() {
    User saved = users.save(new User("alice", "alice@example.com", "$2a$12$hashed"));

    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(users.findByUsernameIgnoreCase("alice"))
        .hasValueSatisfying(u -> {
          assertThat(u.getEmail()).isEqualTo("alice@example.com");
          assertThat(u.getRoles()).containsExactly(Role.USER);
        });
  }

  @Test
  void duplicateUsernameIsRejected() {
    users.saveAndFlush(new User("alice", "alice@example.com", "$2a$12$hashed"));

    assertThatThrownBy(() ->
        users.saveAndFlush(new User("alice", "other@example.com", "$2a$12$hashed")))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void duplicateEmailIsRejected() {
    users.saveAndFlush(new User("alice", "alice@example.com", "$2a$12$hashed"));

    assertThatThrownBy(() ->
        users.saveAndFlush(new User("bob", "alice@example.com", "$2a$12$hashed")))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void rolesSurviveARoundTrip() {
    User user = new User("carol", "carol@example.com", "$2a$12$hashed");
    user.setRoles(EnumSet.of(Role.USER, Role.ADMIN));
    users.saveAndFlush(user);

    assertThat(users.findByUsernameIgnoreCase("carol"))
        .hasValueSatisfying(u ->
            assertThat(u.getRoles()).containsExactlyInAnyOrder(Role.USER, Role.ADMIN));
  }

  @Test
  void lookupsAreCaseInsensitiveRegardlessOfStoredCase() {
    // Rows written before V9's backfill may carry mixed case; the contract
    // is that lookups find them anyway, on every database collation.
    users.saveAndFlush(new User("Alice", "Alice@Example.com", "$2a$12$hashed"));

    assertThat(users.findByUsernameIgnoreCase("alice")).isPresent();
    assertThat(users.findByUsernameIgnoreCase("ALICE")).isPresent();
    assertThat(users.findByEmailIgnoreCase("alice@example.com")).isPresent();
    assertThat(users.findByEmailIgnoreCase("ALICE@EXAMPLE.COM")).isPresent();
    assertThat(users.findByUsernameIgnoreCase("nobody")).isEmpty();
  }
}
