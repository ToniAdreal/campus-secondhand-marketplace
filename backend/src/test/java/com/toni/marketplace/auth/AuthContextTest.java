package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the real application context: validates Flyway migrations (V1 + V2),
 * JPA mappings, and the auth bean wiring from application.yml.
 */
@SpringBootTest
class AuthContextTest {

  @Autowired
  private JwtTokenService jwt;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtProperties props;

  @Test
  void authBeansAreWiredAndPropertiesBound() {
    assertThat(jwt).isNotNull();
    assertThat(passwords).isNotNull();
    assertThat(props.getAccessTtl()).isEqualTo(Duration.ofMinutes(15));
    assertThat(props.getRefreshTtl()).isEqualTo(Duration.ofDays(7));
    assertThat(props.getSecret()).isNotBlank();
  }
}
