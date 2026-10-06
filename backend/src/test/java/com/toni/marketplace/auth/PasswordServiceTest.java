package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Pure unit tests — no Spring context. */
class PasswordServiceTest {

  private final PasswordService passwords = new PasswordService();

  @Test
  void encodedPasswordVerifies() {
    String hash = passwords.encode("correct-horse-9");

    assertThat(passwords.matches("correct-horse-9", hash)).isTrue();
  }

  @Test
  void wrongPasswordDoesNotVerify() {
    String hash = passwords.encode("correct-horse-9");

    assertThat(passwords.matches("wrong-password", hash)).isFalse();
  }

  @Test
  void hashIsSaltedAndNeverPlaintext() {
    String first = passwords.encode("same-password");
    String second = passwords.encode("same-password");

    assertThat(first).isNotEqualTo(second); // random salt per encode
    assertThat(first).doesNotContain("same-password");
    assertThat(first).startsWith("$2a$12$"); // BCrypt, cost 12
  }
}
