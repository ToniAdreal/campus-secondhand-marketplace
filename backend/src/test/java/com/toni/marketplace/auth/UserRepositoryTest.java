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
    assertThat(users.findByUsername("alice"))
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

    assertThat(users.findByUsername("carol"))
        .hasValueSatisfying(u ->
            assertThat(u.getRoles()).containsExactlyInAnyOrder(Role.USER, Role.ADMIN));
  }
}
