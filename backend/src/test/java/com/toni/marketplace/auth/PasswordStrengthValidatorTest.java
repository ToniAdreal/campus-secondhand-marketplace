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
    assertThat(PasswordStrengthValidator.check("abcd1234efgh")).isEmpty();
  }

  @Test
  void lettersOnlyIsRejected() {
    assertThat(PasswordStrengthValidator.check("abcdefghijkl"))
        .hasValue("password must contain both letters and digits");
  }

  @Test
  void digitsOnlyIsRejected() {
    assertThat(PasswordStrengthValidator.check("876543218765"))
        .hasValue("password must contain both letters and digits");
  }

  @Test
  void symbolsWithoutLettersAndDigitsAreRejected() {
    assertThat(PasswordStrengthValidator.check("!@#$%^&*!@#$"))
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
    // "xpassword1yz" is not in the blocklist; it passes on composition.
    assertThat(PasswordStrengthValidator.check("xpassword1yz")).isEmpty();
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
    assertThat(PasswordStrengthValidator.check("P\u00e4ssw0rt1234")).isEmpty();
  }

  @Test
  void nullAndBlankDelegateToNotBlank() {
    // @NotBlank on the request record owns these; the validator stays silent.
    assertThat(PasswordStrengthValidator.check(null)).isEmpty();
    assertThat(PasswordStrengthValidator.check("   ")).isEmpty();
  }

  @Test
  void requireStrongThrowsWithTheUserFacingReason() {
    assertThatThrownBy(() -> PasswordStrengthValidator.requireStrong("abcdefghijkl"))
        .isInstanceOf(WeakPasswordException.class)
        .hasMessage("password must contain both letters and digits");
  }

  @Test
  void elevenCharactersIsTooShort() {
    // 11 chars, letters + digits, not blocklisted — only the length rule
    // (backlog #131) can fail it.
    assertThat(PasswordStrengthValidator.check("abcd1234efg"))
        .hasValue("password must be at least 12 characters");
  }

  @Test
  void twelveCharactersPassesTheLengthRule() {
    assertThat(PasswordStrengthValidator.check("abcd1234efgh")).isEmpty();
  }

  @Test
  void lengthReasonWinsOverTheCompositionReason() {
    // Short AND letters-only: the length reason is reported, not the
    // letters-and-digits reason.
    assertThat(PasswordStrengthValidator.check("abcdefgh"))
        .hasValue("password must be at least 12 characters");
  }

  @Test
  void blocklistReasonWinsOverTheLengthReason() {
    // Every blocklist entry is shorter than the minimum; the blocklist is
    // evaluated first so its more specific message stays reachable.
    assertThat(PasswordStrengthValidator.check("qwerty123"))
        .hasValue("password is too common, choose a less predictable one");
  }

  @Test
  void configuredMinimumLengthIsHonored() {
    assertThat(PasswordStrengthValidator.check("abcd1234", 8)).isEmpty();
    assertThat(PasswordStrengthValidator.check("abcd1234efgh", 16))
        .hasValue("password must be at least 16 characters");
    assertThatThrownBy(() -> PasswordStrengthValidator.requireStrong("abcd1234efg", 12))
        .isInstanceOf(WeakPasswordException.class)
        .hasMessage("password must be at least 12 characters");
  }

  @Test
  void twoCharacterPasswordNoLongerPasses() {
    // The #131 gap: "a1" satisfied letter+digit and was not blocklisted.
    assertThat(PasswordStrengthValidator.check("a1"))
        .hasValue("password must be at least 12 characters");
  }
}
