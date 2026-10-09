package com.toni.marketplace.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Password-reset policy (backlog #90). The token lifetime is deliberately
 * short — a reset token is a bearer credential for a full password change,
 * so it lives minutes, not days (contrast the refresh-token family's 30 d
 * absolute lifetime). Override with
 * {@code app.auth.password-reset.token-ttl=15m}.
 */
@ConfigurationProperties(prefix = "app.auth.password-reset")
public class PasswordResetProperties {

  /** How long an issued reset token stays confirmable. Must be positive. */
  private Duration tokenTtl = Duration.ofMinutes(30);

  public Duration getTokenTtl() {
    return tokenTtl;
  }

  public void setTokenTtl(Duration tokenTtl) {
    this.tokenTtl = tokenTtl;
  }
}
