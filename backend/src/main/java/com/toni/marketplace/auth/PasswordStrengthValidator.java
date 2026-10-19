package com.toni.marketplace.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>Breached-password screening (backlog #197): the overloads taking a
 * {@link BreachedPasswordChecker} add a corpus check AFTER the rules
 * above pass — a candidate is hashed with SHA-1, only the first five
 * hex characters go to the checker (k-anonymity), and a suffix match
 * rejects it with the same exception/envelope as every other strength
 * failure. The default checker is the offline
 * {@link LocalDenylistBreachedPasswordChecker} (a small committed
 * denylist — a floor, not a corpus); a real HIBP-backed checker is an
 * operator opt-in, never silently enabled. Checker failure follows the
 * checker's own policy: fail-open-with-log for a remote checker (an
 * outage must not lock credential setting), fail-closed for the local
 * denylist (it cannot be down). Neither the password nor its full hash
 * is ever logged — the fail-open log line carries neither.
 *
 * <p>Honest scope: the local rules are cheap heuristics, not an entropy
 * estimator (e.g. zxcvbn). The blocklist is a starter set of the worst
 * offenders, deliberately small so it stays auditable.
 */
public final class PasswordStrengthValidator {

  private static final Logger log = LoggerFactory.getLogger(PasswordStrengthValidator.class);

  /** Default minimum password length (backlog #131). */
  public static final int DEFAULT_MIN_LENGTH = 12;

  /** Hex characters of the SHA-1 hash sent to a breach checker (k-anonymity prefix). */
  static final int BREACH_PREFIX_LENGTH = 5;

  /** User-facing reason when a candidate appears in a breach corpus / denylist. */
  static final String BREACHED_REASON =
      "password has appeared in a known data breach, choose a different one";

  /** User-facing reason when the fail-closed local screen itself fails. */
  static final String BREACH_CHECK_FAILED_REASON =
      "password could not be checked against the breached-password list, choose a different one";

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

  /**
   * Checks the policy AND the breached-password screen (backlog #197).
   * The local rules run first — a candidate already rejected on length,
   * composition or the blocklist never reaches the checker. A null
   * checker skips the screen (hand-built call sites without the bean);
   * the service layer always passes one, falling back to the local
   * denylist.
   */
  public static Optional<String> check(String password, int minLength,
                                       BreachedPasswordChecker checker) {
    Optional<String> local = check(password, minLength);
    if (local.isPresent() || checker == null || password == null || password.isBlank()) {
      return local;
    }
    String sha1 = sha1Hex(password);
    String prefix = sha1.substring(0, BREACH_PREFIX_LENGTH);
    String suffix = sha1.substring(BREACH_PREFIX_LENGTH);
    Set<String> suffixes;
    try {
      suffixes = checker.suffixesForPrefix(prefix);
    } catch (RuntimeException failure) {
      if (checker.failClosed()) {
        // The local denylist cannot be "down": a failure here is a bug,
        // and the screen fails closed rather than silently skipping.
        return Optional.of(BREACH_CHECK_FAILED_REASON);
      }
      // Fail-open for a remote checker: an outage must not lock every
      // credential-setting path. The log line deliberately carries
      // neither the password nor any part of its hash.
      log.warn("Breached-password check unavailable; allowing the password"
          + " on the strength rules alone (fail-open)");
      return Optional.empty();
    }
    if (suffixes != null && suffixes.contains(suffix)) {
      return Optional.of(BREACHED_REASON);
    }
    return Optional.empty();
  }

  /** Uppercase SHA-1 hex of the candidate — computed locally, never logged. */
  static String sha1Hex(String password) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      return HexFormat.of().withUpperCase()
          .formatHex(digest.digest(password.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // SHA-1 is a required algorithm on every Java platform.
      throw new IllegalStateException("SHA-1 unavailable", e);
    }
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

  /** Like {@link #check(String, int, BreachedPasswordChecker)}, but throws. */
  public static void requireStrong(String password, int minLength,
                                   BreachedPasswordChecker checker) {
    check(password, minLength, checker).ifPresent(reason -> {
      throw new WeakPasswordException(reason);
    });
  }
}
