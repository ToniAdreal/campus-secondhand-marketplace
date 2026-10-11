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
 * <p>Rules, in evaluation order: a password must not be one of a small
 * curated blocklist of the most commonly abused passwords; it must be at
 * least the configured minimum length (backlog #131 — default
 * {@value #DEFAULT_MIN_LENGTH} characters, tunable via
 * {@code app.auth.password-min-length}); and it must contain at least one
 * letter and at least one digit. The blocklist is checked BEFORE the
 * length rule on purpose: every blocklist entry is shorter than the
 * minimum, so a length-first order would make the blocklist (and its more
 * helpful message) unreachable. The length rule in turn is checked before
 * the composition rule, so a short password reports its length problem
 * rather than a composition problem it may also have.
 *
 * <p>Honest scope: this is a cheap heuristic, not a breach-corpus lookup
 * (e.g. HaveIBeenPwned k-anonymity) or an entropy estimator (e.g. zxcvbn) —
 * those are follow-ups. The blocklist is a starter set of the worst
 * offenders, deliberately small so it stays auditable.
 */
public final class PasswordStrengthValidator {

  /** Default minimum password length (backlog #131). */
  public static final int DEFAULT_MIN_LENGTH = 12;

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
  /** Checks against the {@link #DEFAULT_MIN_LENGTH default} minimum length. */
  public static Optional<String> check(String password) {
    return check(password, DEFAULT_MIN_LENGTH);
  }

  /**
   * Checks the password against the policy with an explicit minimum length
   * (the configured {@code app.auth.password-min-length} at the service
   * call sites). A non-positive minimum is a misconfiguration and falls
   * back to the default rather than silently disabling the rule.
   */
  public static Optional<String> check(String password, int minLength) {
    if (password == null || password.isBlank()) {
      return Optional.empty();
    }
    if (BLOCKLIST.contains(password.toLowerCase(Locale.ROOT))) {
      return Optional.of("password is too common, choose a less predictable one");
    }
    int effectiveMin = minLength > 0 ? minLength : DEFAULT_MIN_LENGTH;
    if (password.length() < effectiveMin) {
      return Optional.of("password must be at least " + effectiveMin + " characters");
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
    }
    if (hasLetter && hasDigit) {
      return Optional.empty();
    }
    return Optional.of("password must contain both letters and digits");
  }

  /** Like {@link #check(String)}, but throws {@link WeakPasswordException}
   * on a weak password so call sites stay one line. */
  public static void requireStrong(String password) {
    requireStrong(password, DEFAULT_MIN_LENGTH);
  }

  /** Like {@link #check(String, int)}, but throws on a weak password. */
  public static void requireStrong(String password, int minLength) {
    check(password, minLength).ifPresent(reason -> {
      throw new WeakPasswordException(reason);
    });
  }
}
