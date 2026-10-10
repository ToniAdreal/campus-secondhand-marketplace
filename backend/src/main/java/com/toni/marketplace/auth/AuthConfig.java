package com.toni.marketplace.auth;

import java.time.Clock;
import javax.crypto.SecretKey;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({JwtProperties.class, AuthRateLimitProperties.class,
    LoginLockoutProperties.class, TotpLockoutProperties.class,
    TotpEncryptionProperties.class, PasswordResetProperties.class,
    TrustedProxiesProperties.class, RefreshCookieProperties.class})
public class AuthConfig {

  /**
   * Application clock, exposed as a bean so time-dependent components (the
   * JWT service, the auth rate limiter) can be driven by a controllable
   * clock in tests.
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public PasswordService passwordService() {
    return new PasswordService();
  }

  /**
   * Token bucket for the credential endpoints
   * ({@code POST /api/auth/login}, {@code POST /api/auth/register}) —
   * default 5 attempts/minute per client IP.
   */
  @Bean
  public AuthRateLimiter credentialRateLimiter(Clock clock, AuthRateLimitProperties props) {
    return new AuthRateLimiter(clock, props);
  }

  /**
   * Separate token bucket for {@code POST /api/auth/refresh} (default
   * 30/minute per client IP). The refresh endpoint is its own
   * unauthenticated brute-force surface: it must not share the tight
   * credential bucket (legitimate multi-tab clients refresh routinely),
   * and the credential bucket must not be consumed by refresh traffic.
   */
  @Bean
  public AuthRateLimiter refreshRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties refreshProps = new AuthRateLimitProperties();
    refreshProps.setEnabled(props.isEnabled());
    refreshProps.setAttemptsPerMinute(props.getRefreshAttemptsPerMinute());
    return new AuthRateLimiter(clock, refreshProps);
  }

  /**
   * Separate token bucket for {@code POST /api/messages} (default 30
   * sends/minute per authenticated user). Keyed on the JWT principal id —
   * message spam is a per-user abuse surface, and keying by IP would let
   * one NAT address starve every user behind it (or let one user hide in
   * their household's shared bucket).
   */
  @Bean
  public AuthRateLimiter messageRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties messageProps = new AuthRateLimitProperties();
    messageProps.setEnabled(props.isEnabled());
    messageProps.setAttemptsPerMinute(props.getMessageAttemptsPerMinute());
    return new AuthRateLimiter(clock, messageProps);
  }

  /**
   * Separate token bucket for the password-reset endpoints (backlog #90):
   * {@code POST /api/auth/password-reset} and
   * {@code POST /api/auth/password-reset/confirm} share it (default
   * 5/minute per client IP). The request endpoint is a mail-bombing
   * surface and the confirm endpoint a token-guessing surface; neither
   * may consume the credential bucket, and reset traffic must not be
   * able to starve logins either.
   */
  @Bean
  public AuthRateLimiter passwordResetRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties resetProps = new AuthRateLimitProperties();
    resetProps.setEnabled(props.isEnabled());
    resetProps.setAttemptsPerMinute(props.getPasswordResetAttemptsPerMinute());
    return new AuthRateLimiter(clock, resetProps);
  }

  /**
   * Separate token bucket for the second-factor exchange
   * {@code POST /api/auth/2fa/authenticate} (backlog #101; default
   * 5/minute per client IP). The endpoint is an online-guessing surface
   * (5-minute challenge Bearer <redacted> for a 6-digit code); it must not
   * share the credential bucket — a burst of failed exchanges must not
   * starve the same IP's logins, and login traffic must not eat the
   * exchange budget. The per-account half of #101 (a failure counter
   * that locks the exchange) lives in {@code AuthService} — see
   * {@link TotpLockoutProperties}.
   */
  @Bean
  public AuthRateLimiter totpRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties totpProps = new AuthRateLimitProperties();
    totpProps.setEnabled(props.isEnabled());
    totpProps.setAttemptsPerMinute(props.getTotpAttemptsPerMinute());
    return new AuthRateLimiter(clock, totpProps);
  }

  /**
   * AES-256-GCM cipher for TOTP secrets at rest (backlog #89). Constructing
   * it validates the configured key (Base64, exactly 32 bytes), so a
   * malformed {@code APP_TOTP_ENCRYPTION_KEY} fails the boot on every
   * profile; the committed dev placeholder is rejected on the mysql
   * profile by {@link TotpEncryptionKeyStartupCheck}.
   */
  @Bean
  public TotpSecretCipher totpSecretCipher(TotpEncryptionProperties props) {
    return new TotpSecretCipher(props.getEncryptionKey());
  }

  @Bean
  public JwtTokenService jwtTokenService(JwtProperties props,
                                         RefreshTokenRepository refreshTokens,
                                         UserRepository users,
                                         Clock clock,
                                         AuthMetrics metrics) {
    SecretKey key = JwtTokenService.keyFromBase64(props.getSecret());
    // Key rotation (backlog #113): a blank/absent previous secret means no
    // rotation is in progress — the previous key stays unset and old-kid
    // tokens get the plain 401.
    SecretKey previousKey = props.getPreviousSecret() == null || props.getPreviousSecret().isBlank()
        ? null : JwtTokenService.keyFromBase64(props.getPreviousSecret());
    return new JwtTokenService(key, props.getCurrentKid(), previousKey, props.getPreviousKid(),
        props.getPreviousAcceptUntil(),
        props.getAccessTtl(), props.getRefreshTtl(),
        props.getRefreshGraceWindow(), props.getRefreshMaxAge(), props.getTotpChallengeTtl(),
        refreshTokens, users, clock, metrics);
  }
}
