package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import com.toni.marketplace.common.WeakPasswordException;

/**
 * Unit matrix for the shared password-strength policy. Pure — no Spring
 * context, so the same rules registration and (later) password change
 * evaluate are pinned here.
 */
class PasswordStrengthValidatorTest {

  @Test
  void lettersPlusDigitsIsAccepted() {
    assertThat(PasswordStrengthValidator.check("abcd1234")).isEmpty();
  }

  @Test
  void lettersOnlyIsRejected() {
    assertThat(PasswordStrengthValidator.check("abcdefgh"))
        .hasValue("password must contain both letters and digits");
  }

  @Test
  void digitsOnlyIsRejected() {
    assertThat(PasswordStrengthValidator.check("87654321"))
        .hasValue("password must contain both letters and digits");
  }

  @Test
  void symbolsWithoutLettersAndDigitsAreRejected() {
    assertThat(PasswordStrengthValidator.check("!@#$%^&*"))
        .hasValue("password must contain both letters and digits");
  }

  @Test
  void blocklistedPasswordIsRejectedEvenWithLettersAndDigits() {
    // "qwerty123" satisfies the composition rule — the blocklist is the
    // reason it fails.
    assertThat(PasswordStrengthValidator.check("qwerty123"))
        .hasValue("password is too common, choose a less predictable one");
  }

  @Test
  void blocklistMatchIsCaseInsensitive() {
    assertThat(PasswordStrengthValidator.check("Password1"))
        .hasValue("password is too common, choose a less predictable one");
  }

  @Test
  void blocklistIsExactMatchNotSubstring() {
    // "xpassword1y" is not in the blocklist; it passes on composition.
    assertThat(PasswordStrengthValidator.check("xpassword1y")).isEmpty();
  }

  @Test
  void everyBlocklistEntryIsRejected() {
    // Plain loop rather than @ParameterizedTest — junit-jupiter-params is a
    // transitive detail of spring-boot-starter-test, not a declared dep.
    for (String password : new String[]{"password", "password123", "12345678",
        "qwerty", "abc123", "letmein", "admin123"}) {
      assertThat(PasswordStrengthValidator.check(password))
          .as("blocklist entry %s", password)
          .hasValue("password is too common, choose a less predictable one");
    }
  }

  @Test
  void nonAsciiLettersCountAsLetters() {
    assertThat(PasswordStrengthValidator.check("P\u00e4ssw0rt")).isEmpty();
  }

  @Test
  void nullAndBlankDelegateToNotBlank() {
    // @NotBlank on the request record owns these; the validator stays silent.
    assertThat(PasswordStrengthValidator.check(null)).isEmpty();
    assertThat(PasswordStrengthValidator.check("   ")).isEmpty();
  }

  @Test
  void requireStrongThrowsWithTheUserFacingReason() {
    assertThatThrownBy(() -> PasswordStrengthValidator.requireStrong("abcdefgh"))
        .isInstanceOf(WeakPasswordException.class)
        .hasMessage("password must contain both letters and digits");
  }
}
