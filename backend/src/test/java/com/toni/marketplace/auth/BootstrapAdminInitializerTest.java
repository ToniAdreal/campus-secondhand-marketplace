package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.EnumSet;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Backlog #213: the opt-in first-ADMIN seed. Unit matrix over
 * {@link BootstrapAdminInitializer#seed()} with a mocked repository — the
 * contract is (a) strict no-op when unset, (b) fail-fast on a partial or
 * weak configuration, (c) creation stores a BCrypt hash and the ADMIN
 * role, (d) an existing account is promoted WITHOUT its password hash
 * ever being touched, and (e) an existing ADMIN is left completely alone.
 */
class BootstrapAdminInitializerTest {

  private UserRepository users;
  private PasswordService passwords;
  private BootstrapAdminProperties properties;

  @BeforeEach
  void setUp() {
    users = mock(UserRepository.class);
    passwords = new PasswordService();
    properties = new BootstrapAdminProperties();
  }

  private BootstrapAdminInitializer initializer() {
    return new BootstrapAdminInitializer(properties, users, passwords);
  }

  private void configure(String username, String email, String password) {
    properties.setAdminUsername(username);
    properties.setAdminEmail(email);
    properties.setAdminPassword(password);
  }

  @Test
  void unsetConfigurationIsAStrictNoOp() {
    initializer().seed();

    verify(users, never()).save(any());
    verify(users, never()).findByUsernameIgnoreCase(any());
    verify(users, never()).findByEmailIgnoreCase(any());
  }

  @Test
  void partialConfigurationFailsFast() {
    configure("rootadmin", "", "");

    assertThatThrownBy(() -> initializer().seed())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("all be set");
    verify(users, never()).save(any());
  }

  @Test
  void shortPasswordFailsFast() {
    configure("rootadmin", "root@example.com", "short");

    assertThatThrownBy(() -> initializer().seed())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("at least 12 characters");
    verify(users, never()).save(any());
  }

  @Test
  void createsAdminWithHashedPasswordAndVerifiedEmail() {
    configure("rootadmin", "root@example.com", "BootstrapPass123");
    when(users.findByUsernameIgnoreCase("rootadmin")).thenReturn(Optional.empty());
    when(users.findByEmailIgnoreCase("root@example.com")).thenReturn(Optional.empty());

    initializer().seed();

    ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
    verify(users).save(saved.capture());
    User admin = saved.getValue();
    assertThat(admin.getUsername()).isEqualTo("rootadmin");
    assertThat(admin.getEmail()).isEqualTo("root@example.com");
    assertThat(admin.getRoles()).containsExactlyInAnyOrder(Role.USER, Role.ADMIN);
    assertThat(admin.isEmailVerified()).isTrue();
    // Only the hash is stored — and it verifies against the raw password.
    assertThat(admin.getPasswordHash()).isNotEqualTo("BootstrapPass123");
    assertThat(passwords.matches("BootstrapPass123", admin.getPasswordHash())).isTrue();
  }

  @Test
  void existingUserIsPromotedWithoutTouchingThePasswordHash() {
    configure("rootadmin", "root@example.com", "BootstrapPass123");
    User existing = new User("rootadmin", "root@example.com", "pre-existing-hash");
    existing.setRoles(EnumSet.of(Role.USER));
    when(users.findByUsernameIgnoreCase("rootadmin")).thenReturn(Optional.of(existing));

    initializer().seed();

    verify(users).save(existing);
    assertThat(existing.getRoles()).containsExactlyInAnyOrder(Role.USER, Role.ADMIN);
    assertThat(existing.getPasswordHash()).isEqualTo("pre-existing-hash");
  }

  @Test
  void existingAdminIsLeftCompletelyAlone() {
    configure("rootadmin", "root@example.com", "BootstrapPass123");
    User existing = new User("rootadmin", "root@example.com", "pre-existing-hash");
    existing.setRoles(EnumSet.of(Role.USER, Role.ADMIN));
    when(users.findByUsernameIgnoreCase("rootadmin")).thenReturn(Optional.of(existing));

    initializer().seed();

    verify(users, never()).save(any());
    assertThat(existing.getPasswordHash()).isEqualTo("pre-existing-hash");
  }

  @Test
  void accountFoundByEmailIsPromotedToo() {
    configure("rootadmin", "root@example.com", "BootstrapPass123");
    User existing = new User("operator", "root@example.com", "pre-existing-hash");
    existing.setRoles(EnumSet.of(Role.USER));
    when(users.findByUsernameIgnoreCase("rootadmin")).thenReturn(Optional.empty());
    when(users.findByEmailIgnoreCase("root@example.com")).thenReturn(Optional.of(existing));

    initializer().seed();

    verify(users).save(existing);
    assertThat(existing.getRoles()).contains(Role.ADMIN);
    assertThat(existing.getPasswordHash()).isEqualTo("pre-existing-hash");
  }
}
