package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.toni.marketplace.common.DuplicateUserException;

/**
 * Pure unit tests for the case-normalization wiring in {@link AuthService}:
 * no Spring context, no database. Pins that mixed-case input is canonicalized
 * to lowercase before any repository lookup or save — the MockMvc suite pins
 * the HTTP contract, these pin the exact call sites.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceNormalizationTest {

  @Mock
  private UserRepository users;

  @Mock
  private PasswordService passwords;

  @Mock
  private JwtTokenService jwt;

  @Mock
  private Clock clock;

  @Mock
  private LoginLockoutProperties lockout;

  @InjectMocks
  private AuthService service;

  private static JwtTokenService.TokenPair pair() {
    return new JwtTokenService.TokenPair("access", "refresh",
        Instant.now().plusSeconds(900), Instant.now().plusSeconds(604800));
  }

  @Test
  void registerNormalizesBeforeDuplicateChecksAndSave() {
    when(users.findByUsernameIgnoreCase("alice")).thenReturn(Optional.empty());
    when(users.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.empty());
    when(passwords.encode("s3cret-pass")).thenReturn("$2a$12$hashed");
    when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(jwt.createTokenPair(any(User.class), any(SessionMeta.class))).thenReturn(pair());

    AuthService.AuthResult result = service.register("Alice", "Alice@Example.com", "s3cret-pass");

    // Lookups ran with the canonical form, never the raw mixed-case input.
    verify(users).findByUsernameIgnoreCase("alice");
    verify(users).findByEmailIgnoreCase("alice@example.com");

    ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
    verify(users).save(saved.capture());
    assertThat(saved.getValue().getUsername()).isEqualTo("alice");
    assertThat(saved.getValue().getEmail()).isEqualTo("alice@example.com");
    assertThat(result.user().getUsername()).isEqualTo("alice");
  }

  @Test
  void registerDuplicateCheckSeesThroughCaseVariants() {
    User existing = new User("alice", "alice@example.com", "$2a$12$hashed");
    when(users.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.register("ALICE", "other@example.com", "s3cret-pass"))
        .isInstanceOf(DuplicateUserException.class)
        .hasMessage("username is already taken");
  }

  @Test
  void loginNormalizesTheIdentifierBeforeLookup() {
    User existing = new User("alice", "alice@example.com", "$2a$12$hashed");
    when(users.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(existing));
    when(passwords.matches("s3cret-pass", "$2a$12$hashed")).thenReturn(true);
    when(jwt.createTokenPair(eq(existing), any(SessionMeta.class))).thenReturn(pair());

    AuthService.AuthResult result = ((AuthService.LoginResult.Pair) service.login("ALICE", "s3cret-pass")).result();

    verify(users).findByUsernameIgnoreCase("alice");
    assertThat(result.user()).isSameAs(existing);
  }

  @Test
  void loginByEmailNormalizesTheIdentifierBeforeLookup() {
    User existing = new User("alice", "alice@example.com", "$2a$12$hashed");
    when(users.findByUsernameIgnoreCase("alice@example.com")).thenReturn(Optional.empty());
    when(users.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(existing));
    when(passwords.matches("s3cret-pass", "$2a$12$hashed")).thenReturn(true);
    when(jwt.createTokenPair(eq(existing), any(SessionMeta.class))).thenReturn(pair());

    AuthService.AuthResult result = ((AuthService.LoginResult.Pair) service.login("ALICE@EXAMPLE.COM", "s3cret-pass")).result();

    verify(users).findByUsernameIgnoreCase("alice@example.com");
    verify(users).findByEmailIgnoreCase("alice@example.com");
    assertThat(result.user()).isSameAs(existing);
  }
}
