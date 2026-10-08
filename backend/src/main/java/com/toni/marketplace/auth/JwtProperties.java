package com.toni.marketplace.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code app.jwt.*} from application.yml. */
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

  /**
   * Base64-encoded HS256 key (≥ 256 bits). Dev default is a throwaway key;
   * production MUST override via the APP_JWT_SECRET environment variable.
   */
  private String secret;

  private Duration accessTtl = Duration.ofMinutes(15);

  private Duration refreshTtl = Duration.ofDays(7);

  /**
   * How long after a rotation the just-replaced refresh token is still
   * accepted once more (concurrent-tab grace; backlog #39). A replayed
   * token whose successor is still live and whose revocation is no older
   * than this rotates the live token instead of triggering theft detection.
   * Replays after the window still kill the family. Default 60 seconds.
   */
  private Duration refreshGraceWindow = Duration.ofSeconds(60);

  /**
   * Absolute lifetime of a refresh-token family, measured from the original
   * login (backlog #61). A refresh presented past this point is rejected
   * with 401 and the family is revoked — even when the presented refresh
   * token itself is unexpired — forcing a fresh login. Rotation alone no
   * longer keeps a session alive forever. Default 30 days.
   */
  private Duration refreshMaxAge = Duration.ofDays(30);

  /**
   * Lifetime of the signed 2FA challenge minted by POST /api/auth/login for
   * TOTP-enabled accounts (backlog #78). The challenge is presented to POST
   * /api/auth/2fa/authenticate together with the 6-digit code; it is a
   * signed JWT (type {@code totp-challenge}) and is stateless — replay
   * inside the window is harmless because every use still requires a fresh
   * valid TOTP code. Default 5 minutes.
   */
  private Duration totpChallengeTtl = Duration.ofMinutes(5);

  public String getSecret() { return secret; }
  public void setSecret(String secret) { this.secret = secret; }
  public Duration getAccessTtl() { return accessTtl; }
  public void setAccessTtl(Duration accessTtl) { this.accessTtl = accessTtl; }
  public Duration getRefreshTtl() { return refreshTtl; }
  public void setRefreshTtl(Duration refreshTtl) { this.refreshTtl = refreshTtl; }
  public Duration getRefreshGraceWindow() { return refreshGraceWindow; }
  public void setRefreshGraceWindow(Duration refreshGraceWindow) {
    this.refreshGraceWindow = refreshGraceWindow;
  }
  public Duration getRefreshMaxAge() { return refreshMaxAge; }
  public void setRefreshMaxAge(Duration refreshMaxAge) { this.refreshMaxAge = refreshMaxAge; }
  public Duration getTotpChallengeTtl() { return totpChallengeTtl; }
  public void setTotpChallengeTtl(Duration totpChallengeTtl) {
    this.totpChallengeTtl = totpChallengeTtl;
  }
}
