package com.toni.marketplace.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Trusted-device policy for the TOTP challenge (backlog #199).
 *
 * <p>A trusted device still requires the password at login; trust
 * skips only the TOTP step. The trust token lives in its own httpOnly
 * cookie and rotates on every trusted use; the lifetime is sliding —
 * each trusted login extends {@code expiresAt} to {@code now + ttl} —
 * so a device used at least once per {@code ttl} stays trusted, and
 * an untouched device falls back to the full challenge after it.
 * Default 30 days. There is deliberately no "enabled" switch: trust
 * is created only by the user's explicit opt-in at the challenge
 * exchange, and rows are revocable at any time from the sessions
 * surface, so an operator kill-switch would add a second, drifting
 * source of truth.
 */
@ConfigurationProperties(prefix = "app.auth.trusted-device")
public class TrustedDeviceProperties {

  /** How long a trust row lives without a trusted use. Must be positive. */
  private Duration ttl = Duration.ofDays(30);

  public Duration getTtl() {
    return ttl;
  }

  public void setTtl(Duration ttl) {
    this.ttl = ttl;
  }
}
