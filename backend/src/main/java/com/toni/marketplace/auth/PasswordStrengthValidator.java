package com.toni.marketplace.auth;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import com.toni.marketplace.common.WeakPasswordException;

/**
 * Reusable password-strength policy, shared by registration and the planned
 * password-change endpoint (#38). Pure — no Spring, no I/O, no secrets —
 * so the web layer, the service layer and tests all evaluate exactly the
 * same rules.
 *
 * <p>Rules: a password must contain at least one letter and at least one
 * digit, and must not be one of a small curated blocklist of the most
 * commonly abused passwords.
 *
 * <p>Honest scope: this is a cheap heuristic, not a breach-corpus lookup
 * (e.g. HaveIBeenPwned k-anonymity) or an entropy estimator (e.g. zxcvbn) —
 * those are follow-ups. The blocklist is a starter set of the worst
 * offenders, deliberately small so it stays auditable.
 */
public final class PasswordStrengthValidator {

  private static final Set<String> BLOCKLIST = Set.of(
      "password", "password1", "password123",
      "12345678", "123456789", "1234567890",
      "qwerty", "qwerty123",
      "abc123", "letmein",
      "welcome123", "admin123", "1q2w3e4r");

  private PasswordStrengthValidator() {}

  /**
   * Checks the password against the policy. {@link Optional#empty()} means
   * it is strong enough; otherwise the user-facing rejection reason. Null or
   * blank inputs pass here — {@code @NotBlank} on the request record owns
   * those before this is ever reached.
   */
  public static Optional<String> check(String password) {
    if (password == null || password.isBlank()) {
      return Optional.empty();
    }
    if (BLOCKLIST.contains(password.toLowerCase(Locale.ROOT))) {
      return Optional.of("password is too common, choose a less predictable one");
    }
    boolean hasLetter = false;
    boolean hasDigit = false;
    for (int i = 0; i < password.length(); i++) {
      char c = password.charAt(i);
      if (Character.isLetter(c)) {
        hasLetter = true;
      } else if (Character.isDigit(c)) {
        hasDigit = true;
      }
      if (hasLetter && hasDigit) {
        return Optional.empty();
      }
    }
    return Optional.of("password must contain both letters and digits");
  }

  /** Like {@link #check(String)}, but throws {@link WeakPasswordException}
   * on a weak password so call sites stay one line. */
  public static void requireStrong(String password) {
    check(password).ifPresent(reason -> {
      throw new WeakPasswordException(reason);
    });
  }
}
