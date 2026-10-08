package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toni.marketplace.common.PasswordReuseException;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure unit tests (no Spring context) for the password-reuse guard in
 * {@link AuthService#changePassword}. The candidate is compared against the
 * stored hash with {@code PasswordEncoder.matches} before anything is
 * encoded — a reuse must fail fast with no side effects.
 */
@ExtendWith(MockitoExtension.class)
class PasswordReuseUnitTest {

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

  private AuthService auth;

  @BeforeEach
  void setUp() {
    auth = new AuthService(users, passwords, jwt, clock, lockout);
  }

  private User alice() {
    // The id is irrelevant here: the reuse guard throws before the service
    // touches the user row, the token version, or the jwt service.
    return new User("alice", "alice@example.com", "$2a$12$storedhash");
  }

  @Test
  void reusingCurrentPasswordThrowsAndEncodesNothing() {
    User alice = alice();
    when(users.findById(7L)).thenReturn(Optional.of(alice));
    when(passwords.matches("s3cret-pass", "$2a$12$storedhash")).thenReturn(true);

    assertThatThrownBy(() -> auth.changePassword(7L, "s3cret-pass", "s3cret-pass"))
        .isInstanceOf(PasswordReuseException.class)
        .hasMessage("new password must differ from the current password");

    // Fail fast: no re-encode, no row save, no family revocation, no new pair.
    verify(passwords, never()).encode(anyString());
    verify(users, never()).save(alice);
    verify(jwt, never()).revokeAll(7L);
  }

  @Test
  void reuseCheckRunsBeforeStrengthValidation() {
    // A weak-but-identical candidate is rejected for reuse, not weakness —
    // the reuse check sits ahead of PasswordStrengthValidator.
    User alice = alice();
    when(users.findById(7L)).thenReturn(Optional.of(alice));
    when(passwords.matches("abc", "$2a$12$storedhash")).thenReturn(true);

    assertThatThrownBy(() -> auth.changePassword(7L, "abc", "abc"))
        .isInstanceOf(PasswordReuseException.class);

    verify(passwords, never()).encode(anyString());
  }
}
