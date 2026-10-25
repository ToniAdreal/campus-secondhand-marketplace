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
    TrustedProxiesProperties.class, RefreshCookieProperties.class,
    PasswordPolicyProperties.class, EmailVerificationProperties.class,
    DataExportGrantProperties.class, SessionLimitProperties.class,
    BreachedPasswordProperties.class, TrustedDeviceProperties.class,
    CspReportProperties.class, BootstrapAdminProperties.class,
    StepUpProperties.class, PasskeyProperties.class, TelemetryErrorProperties.class})
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
   * Breached-password checker (backlog #197): the offline local denylist
   * by default — no network call, ever. Only the operator's explicit
   * {@code app.auth.breached-password.remote-enabled=true} swaps in the
   * HIBP-shaped remote range checker (fail-open); it is never enabled
   * silently.
   */
  @Bean
  public BreachedPasswordChecker breachedPasswordChecker(BreachedPasswordProperties props) {
    if (props.isRemoteEnabled()) {
      return new HttpBreachedPasswordChecker(props.getBaseUrl(), props.getTimeout());
    }
    return new LocalDenylistBreachedPasswordChecker();
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
   * Separate token bucket for the email-verification endpoints
   * (backlog #138): {@code POST /api/auth/email-verification/resend} and
   * {@code POST /api/auth/email-verification/confirm} share it (default
   * 5/minute per client IP). The resend endpoint is a mail-bombing surface
   * and the confirm endpoint a token-guessing surface; neither may consume
   * the credential bucket, and verification traffic must not be able to
   * starve logins either — the backlog #101 dedicated-bucket pattern.
   */
  @Bean
  public AuthRateLimiter emailVerificationRateLimiter(Clock clock,
                                                      AuthRateLimitProperties props) {
    AuthRateLimitProperties verificationProps = new AuthRateLimitProperties();
    verificationProps.setEnabled(props.isEnabled());
    verificationProps.setAttemptsPerMinute(props.getEmailVerificationAttemptsPerMinute());
    return new AuthRateLimiter(clock, verificationProps);
  }

  /**
   * Separate token bucket for listing reports
   * ({@code POST /api/items/{id}/report}, backlog #160; default 10
   * reports/minute per authenticated user). Keyed on the JWT principal
   * id like the message bucket — report spam is a per-user abuse surface
   * aimed at the ADMIN moderation queue, and keying by IP would let one
   * NAT address starve every reporter behind it.
   */
  @Bean
  public AuthRateLimiter reportRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties reportProps = new AuthRateLimitProperties();
    reportProps.setEnabled(props.isEnabled());
    reportProps.setAttemptsPerMinute(props.getReportAttemptsPerMinute());
    return new AuthRateLimiter(clock, reportProps);
  }

  /**
   * Separate token bucket for the account data export
   * ({@code GET /api/users/me/export}, backlog #172; default 5
   * downloads/minute per authenticated user). Keyed on the JWT
   * principal id like the message bucket — the export is the heaviest
   * read in the app, and keying by IP would let one NAT address starve
   * every downloader behind it.
   */
  @Bean
  public AuthRateLimiter dataExportRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties exportProps = new AuthRateLimitProperties();
    exportProps.setEnabled(props.isEnabled());
    exportProps.setAttemptsPerMinute(props.getDataExportAttemptsPerMinute());
    return new AuthRateLimiter(clock, exportProps);
  }

  /**
   * Separate token bucket for the step-up re-proof endpoint
   * ({@code POST /api/auth/step-up}, backlog #218; default 5
   * attempts/minute per authenticated user). Keyed on the JWT
   * principal id like the message/export buckets — the endpoint
   * verifies the current password, so a stolen Bearer token must not
   * get unlimited guesses at it, and keying by IP would let one NAT
   * address starve every user behind it.
   */
  @Bean
  public AuthRateLimiter stepUpRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties stepUpProps = new AuthRateLimitProperties();
    stepUpProps.setEnabled(props.isEnabled());
    stepUpProps.setAttemptsPerMinute(props.getStepUpAttemptsPerMinute());
    return new AuthRateLimiter(clock, stepUpProps);
  }

  /**
   * Separate token bucket for listing creation
   * ({@code POST /api/items}, backlog #219; default 10 listings/minute
   * per authenticated user). Keyed on the JWT principal id like the
   * message/report buckets — listing spam is a per-user abuse surface,
   * and keying by IP would let one NAT address starve every seller
   * behind it.
   */
  @Bean
  public AuthRateLimiter listingCreateRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties listingProps = new AuthRateLimitProperties();
    listingProps.setEnabled(props.isEnabled());
    listingProps.setAttemptsPerMinute(props.getListingCreateAttemptsPerMinute());
    return new AuthRateLimiter(clock, listingProps);
  }

  /**
   * Separate token bucket for order creation
   * ({@code POST /api/orders}, backlog #219; default 20 orders/minute
   * per authenticated user). Keyed on the JWT principal id like the
   * listing-creation bucket — the abuse surface is an account hammering
   * order rows (and the idempotency/notification work each order
   * triggers), not an IP.
   */
  @Bean
  public AuthRateLimiter orderCreateRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties orderProps = new AuthRateLimitProperties();
    orderProps.setEnabled(props.isEnabled());
    orderProps.setAttemptsPerMinute(props.getOrderCreateAttemptsPerMinute());
    return new AuthRateLimiter(clock, orderProps);
  }

  /**
   * Separate token bucket for the CSP violation report intake
   * ({@code POST /api/security/csp-report}, backlog #204; default 60
   * reports/minute per client IP). The endpoint is public by necessity
   * (browsers send reports unauthenticated), so this bucket is the
   * flood gate for the log/metric pipeline; it must not share any
   * credential bucket, and report traffic must not starve logins.
   */
  @Bean
  public AuthRateLimiter cspReportRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties cspProps = new AuthRateLimitProperties();
    cspProps.setEnabled(props.isEnabled());
    cspProps.setAttemptsPerMinute(props.getCspReportAttemptsPerMinute());
    return new AuthRateLimiter(clock, cspProps);
  }

  /**
   * Separate token bucket for the frontend error telemetry intake
   * ({@code POST /api/telemetry/errors}, backlog #223; default 60
   * beacons/minute per client IP). The endpoint is public by necessity
   * (a crash can happen on an anonymous page), so this bucket is the
   * flood gate for the log/metric pipeline; it must not share any
   * credential bucket, and beacon traffic must not starve logins.
   */
  @Bean
  public AuthRateLimiter telemetryErrorRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties telemetryProps = new AuthRateLimitProperties();
    telemetryProps.setEnabled(props.isEnabled());
    telemetryProps.setAttemptsPerMinute(props.getTelemetryErrorAttemptsPerMinute());
    return new AuthRateLimiter(clock, telemetryProps);
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
