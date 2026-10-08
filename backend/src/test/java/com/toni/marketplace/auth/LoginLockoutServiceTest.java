package com.toni.marketplace.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import com.toni.marketplace.common.AccountLockedException;
import com.toni.marketplace.common.InvalidCredentialsException;

/**
 * Service-level tests for the per-account login lockout (backlog #60).
 * The application {@code Clock} bean is replaced with a {@link MutableClock}
 * so lock durations are driven deterministically. Each test rolls back its
 * fixture user ({@code @Transactional}).
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
    "app.auth.login-lockout.max-attempts=3",
    "app.auth.login-lockout.lock-duration=15m"
})
class LoginLockoutServiceTest {

  @TestConfiguration
  static class ControllableClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  private static final String PASSWORD = "s3cret-pass";

  @Autowired
  private AuthService auth;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private MutableClock clock;

  private User victim;

  @BeforeEach
  void setUp() {
    victim = users.save(new User("victim", "victim@example.com", passwords.encode(PASSWORD)));
  }

  private User reload() {
    return users.findById(victim.getId()).orElseThrow();
  }

  /** Wrong password under the threshold: 401, counter increments, no lock. */
  @Test
  void failuresBelowThresholdStay401AndCountUp() {
    assertThrows(InvalidCredentialsException.class, () -> auth.login("victim", "wrong"));
    assertThrows(InvalidCredentialsException.class, () -> auth.login("victim", "wrong"));
    assertEquals(2, reload().getFailedLoginAttempts());
    assertNull(reload().getLockedUntil());
  }

  /** The Nth consecutive failure locks the account (423), row carries locked_until. */
  @Test
  void nthConsecutiveFailureLocksAccount() {
    int max = 3;
    for (int i = 0; i < max - 1; i++) {
      assertThrows(InvalidCredentialsException.class, () -> auth.login("victim", "wrong"));
    }
    AccountLockedException locked =
        assertThrows(AccountLockedException.class, () -> auth.login("victim", "wrong"));
    assertEquals(15 * 60, locked.getRetryAfterSeconds());
    assertEquals(max, reload().getFailedLoginAttempts());
    assertNotNull(reload().getLockedUntil());
  }

  /** While locked, even the correct password gets 423 — the attacker cannot wait it out by guessing right. */
  @Test
  void lockedAccountRejectsCorrectPassword() {
    lockTheAccount();
    AccountLockedException locked =
        assertThrows(AccountLockedException.class, () -> auth.login("victim", PASSWORD));
    assertTrue(locked.getRetryAfterSeconds() <= 15 * 60 && locked.getRetryAfterSeconds() > 0);
  }

  /** After the lock duration passes, a correct login succeeds and the counter resets. */
  @Test
  void lockExpiresThenLoginSucceedsAndResetsCounter() {
    lockTheAccount();
    clock.advance(Duration.ofMinutes(16));
    auth.login("victim", PASSWORD);
    assertEquals(0, reload().getFailedLoginAttempts());
    assertNull(reload().getLockedUntil());
  }

  /** After expiry, failures start from zero again — the old counter is not resurrected. */
  @Test
  void failuresAfterExpiryStartFresh() {
    lockTheAccount();
    clock.advance(Duration.ofMinutes(16));
    assertThrows(InvalidCredentialsException.class, () -> auth.login("victim", "wrong"));
    assertEquals(1, reload().getFailedLoginAttempts());
    assertNull(reload().getLockedUntil());
  }

  /** A successful login resets the failure counter (no slow-drip lockout of a typo-prone user). */
  @Test
  void successResetsCounter() {
    assertThrows(InvalidCredentialsException.class, () -> auth.login("victim", "wrong"));
    assertThrows(InvalidCredentialsException.class, () -> auth.login("victim", "wrong"));
    auth.login("victim", PASSWORD);
    assertEquals(0, reload().getFailedLoginAttempts());
    // Two more failures: still 401, not a lock — the counter restarted at zero.
    assertThrows(InvalidCredentialsException.class, () -> auth.login("victim", "wrong"));
    assertThrows(InvalidCredentialsException.class, () -> auth.login("victim", "wrong"));
    assertEquals(2, reload().getFailedLoginAttempts());
    assertNull(reload().getLockedUntil());
  }

  /** Unknown identifiers are never tracked: identical 401s forever, never 423 (no enumeration oracle). */
  @Test
  void unknownIdentifierIsNeverLocked() {
    for (int i = 0; i < 10; i++) {
      assertThrows(InvalidCredentialsException.class,
          () -> auth.login("ghost-user", "whatever"));
    }
    // No row could be touched; the victim's row is untouched as well.
    assertEquals(0, reload().getFailedLoginAttempts());
    assertNull(reload().getLockedUntil());
  }

  /** Case variants of the identifier hit the same counter (canonical lookup). */
  @Test
  void caseVariantHitsTheSameCounter() {
    assertThrows(InvalidCredentialsException.class, () -> auth.login("VICTIM", "wrong"));
    assertThrows(InvalidCredentialsException.class, () -> auth.login("victim", "wrong"));
    assertEquals(2, reload().getFailedLoginAttempts());
  }

  private void lockTheAccount() {
    // Test profile sets app.auth.login-lockout.max-attempts=3.
    for (int i = 0; i < 3; i++) {
      try {
        auth.login("victim", "wrong");
      } catch (InvalidCredentialsException | AccountLockedException expected) {
        // The last failure throws AccountLockedException; earlier ones 401.
      }
    }
  }
}
