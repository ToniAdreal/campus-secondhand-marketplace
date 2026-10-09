package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * The default mail sender (backlog #90) delivers nothing — it only logs
 * (token redacted). The one behavior worth pinning: it never throws, so a
 * reset request can never fail because delivery is a no-op demo bean.
 */
class LoggingPasswordResetMailSenderTest {

  @Test
  void sendNeverThrows() {
    User user = new User("alice", "alice@example.com", "hash");
    assertThatCode(() -> new LoggingPasswordResetMailSender()
        .sendPasswordReset(user, "raw-token-that-must-not-be-logged"))
        .doesNotThrowAnyException();
  }
}
