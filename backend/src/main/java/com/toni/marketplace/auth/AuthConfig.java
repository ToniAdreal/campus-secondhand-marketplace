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
   * Separate token bucket for the email-change request endpoint
   * ({@code POST /api/auth/email-change}, backlog #294; default 5
   * requests/minute per client IP). The request endpoint is a
   * mail-bombing surface — each accepted request mails a token to the
   * NEW address — and it previously rode unthrottled while its confirm
   * sibling shared the email-verification bucket. The request gets its
   * own bucket on the backlog #101 pattern: request traffic must
   * neither consume nor be starved by verification/confirm traffic,
   * and a staged change never resets it.
   */
  @Bean
  public AuthRateLimiter emailChangeRequestRateLimiter(Clock clock,
                                                       AuthRateLimitProperties props) {
    AuthRateLimitProperties requestProps = new AuthRateLimitProperties();
    requestProps.setEnabled(props.isEnabled());
    requestProps.setAttemptsPerMinute(props.getEmailChangeRequestAttemptsPerMinute());
    return new AuthRateLimiter(clock, requestProps);
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
   * Separate token bucket for the export-confirm step
   * ({@code POST /api/users/me/export/confirm}, backlog #311; default 5
   * attempts/minute per authenticated user, like the step-up bucket).
   * Keyed on the JWT principal id like the message/export buckets —
   * the confirm verifies the current password plus a second-factor
   * code when 2FA is enabled, so it is a password-plus-code guessing
   * surface for a stolen Bearer token, and keying by IP would let one
   * NAT address starve every confirmer behind it. Deliberately
   * separate from the download bucket (which covers only the
   * {@code GET /api/users/me/export} download itself) and from the
   * step-up bucket (which covers only the re-proof): the step-up gate
   * proves freshness, not rate, so confirm traffic must neither
   * consume nor be starved by re-proof traffic, nor by downloads.
   * Never resets on success: a minted grant must not refill a
   * guesser's budget, and a throttled confirm mints nothing.
   */
  @Bean
  public AuthRateLimiter exportConfirmRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties confirmProps = new AuthRateLimitProperties();
    confirmProps.setEnabled(props.isEnabled());
    confirmProps.setAttemptsPerMinute(props.getExportConfirmAttemptsPerMinute());
    return new AuthRateLimiter(clock, confirmProps);
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
   * Separate token bucket for the password-change endpoint
   * ({@code POST /api/auth/password}, backlog #290; default 5
   * attempts/minute per authenticated user). Keyed on the JWT
   * principal id like the step-up bucket — the endpoint verifies the
   * current password, so a stolen Bearer token must not get unlimited
   * guesses at it, and keying by IP would let one NAT address starve
   * every user behind it. Never reset on success: a completed change
   * must not refill a guesser's budget. Deliberately separate from
   * the step-up bucket (which covers only the re-proof) and the
   * credential bucket, so guessing at one surface can never starve
   * the others.
   */
  @Bean
  public AuthRateLimiter passwordChangeRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties passwordChangeProps = new AuthRateLimitProperties();
    passwordChangeProps.setEnabled(props.isEnabled());
    passwordChangeProps.setAttemptsPerMinute(props.getPasswordChangeAttemptsPerMinute());
    return new AuthRateLimiter(clock, passwordChangeProps);
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
   * Separate token bucket for the order transition POSTs
   * ({@code POST /api/orders/{id}/pay}, {@code /cancel}, {@code /complete},
   * {@code /refund}, {@code /ship}, backlog #228; default 20
   * transitions/minute per authenticated user). The five transitions
   * share this one bucket — they are the money path of a single order —
   * keyed on the JWT principal id like the listing/order-create buckets,
   * so transition traffic neither consumes nor starves the create-order
   * bucket, and one account hammering transitions is throttled as one
   * abuse shape. Never reset on success: an idempotent re-pay must not
   * refill the allowance.
   */
  @Bean
  public AuthRateLimiter orderTransitionRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties transitionProps = new AuthRateLimitProperties();
    transitionProps.setEnabled(props.isEnabled());
    transitionProps.setAttemptsPerMinute(props.getOrderTransitionAttemptsPerMinute());
    return new AuthRateLimiter(clock, transitionProps);
  }

  /**
   * Separate token bucket for the order-tail POSTs
   * ({@code POST /api/orders/{id}/confirm-receipt} and
   * {@code POST /api/orders/{id}/handover-code/regenerate}, backlog
   * #289; default 20 transitions/minute per authenticated user). The
   * two shapes share this one bucket — they are the tail of the same
   * money path — keyed on the JWT principal id like the
   * order-transition bucket, so receipt/regenerate traffic neither
   * consumes nor starves the #228 transition bucket, and vice versa.
   * Never reset on success: an idempotent re-confirm must not refill
   * the allowance.
   */
  @Bean
  public AuthRateLimiter orderReceiptRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties receiptProps = new AuthRateLimitProperties();
    receiptProps.setEnabled(props.isEnabled());
    receiptProps.setAttemptsPerMinute(props.getOrderReceiptAttemptsPerMinute());
    return new AuthRateLimiter(clock, receiptProps);
  }

  /**
   * Separate token bucket for the item mutation POSTs/PATCH
   * ({@code POST /api/items/{id}/photo}, {@code /photos}, {@code /renew},
   * {@code /publish} and {@code PATCH /api/items/{id}/status}, backlog
   * #228; default 30 mutations/minute per authenticated user). The
   * mutations share this one bucket — one account editing its own
   * listings — keyed on the JWT principal id like the listing-create
   * bucket, so mutation traffic neither consumes nor starves listing
   * creation, and a legitimate photo-upload burst stays under the
   * generous ceiling. Never reset on success.
   */
  @Bean
  public AuthRateLimiter itemMutationRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties mutationProps = new AuthRateLimitProperties();
    mutationProps.setEnabled(props.isEnabled());
    mutationProps.setAttemptsPerMinute(props.getItemMutationAttemptsPerMinute());
    return new AuthRateLimiter(clock, mutationProps);
  }

  /**
   * Separate token bucket for the listing-edit writes (backlog #307):
   * {@code PATCH /api/items/{id}}, {@code PUT
   * /api/items/{id}/photos/order} and {@code DELETE
   * /api/items/{id}/photos/{photoId}} share it (default 30 writes/minute
   * per authenticated user, like the #228 mutation bucket). Keyed on the
   * JWT principal id like the mutation bucket — one account rewriting its
   * own listings and galleries at volume is a per-account abuse surface,
   * and keying by IP would let one NAT address starve every seller behind
   * it. Deliberately separate from the #228 mutation bucket (photo
   * uploads, renew, publish and the status flip stay there) and from the
   * #219 listing-create bucket (creating a listing stays there), so edit
   * traffic can never starve them, nor vice versa. Never resets on
   * success: a completed edit, reorder or photo removal must not refill
   * the allowance.
   */
  @Bean
  public AuthRateLimiter itemEditRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties editProps = new AuthRateLimitProperties();
    editProps.setEnabled(props.isEnabled());
    editProps.setAttemptsPerMinute(props.getItemEditAttemptsPerMinute());
    return new AuthRateLimiter(clock, editProps);
  }

  /**
   * Separate token bucket for the social / money-adjacent creation
   * writes (backlog #282): {@code POST /api/items/{id}/offers},
   * {@code POST /api/orders/{id}/review}, {@code POST
   * /api/orders/{id}/dispute}, {@code POST
   * /api/disputes/{id}/evidence} and {@code POST
   * /api/seller/reports/{id}/appeal} share it (default 20
   * writes/minute per authenticated user). Keyed on the JWT
   * principal id like the message/report buckets — the abuse
   * surface is one account flooding the negotiation, review,
   * dispute and appeal surfaces, not an IP — and it neither
   * consumes nor starves the neighbouring write buckets. Never
   * resets on success.
   */
  @Bean
  public AuthRateLimiter socialWriteRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties socialProps = new AuthRateLimitProperties();
    socialProps.setEnabled(props.isEnabled());
    socialProps.setAttemptsPerMinute(props.getSocialWriteAttemptsPerMinute());
    return new AuthRateLimiter(clock, socialProps);
  }

  /**
   * Separate token bucket for the social-graph writes (backlog #308):
   * {@code PUT/DELETE /api/users/me/favorites/{itemId}}, {@code
   * POST/DELETE /api/users/me/follows/{sellerId}} and {@code POST/DELETE
   * /api/users/{id}/block} share it (default 20 writes/minute per
   * authenticated user, like the #282 bucket). Keyed on the JWT
   * principal id like the social-write bucket — the abuse surface is
   * one account hammering the save / follow / block surfaces, not an
   * IP — and it neither consumes nor starves the #282 creation bucket
   * (filing an offer, a review, a dispute, evidence or an appeal
   * stays there) or any neighbouring write bucket. The favorites,
   * follows and blocks list GETs are reads and stay unthrottled.
   * Never resets on success: a completed save, follow or block must
   * not refill the allowance.
   */
  @Bean
  public AuthRateLimiter socialGraphRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties graphProps = new AuthRateLimitProperties();
    graphProps.setEnabled(props.isEnabled());
    graphProps.setAttemptsPerMinute(props.getSocialGraphAttemptsPerMinute());
    return new AuthRateLimiter(clock, graphProps);
  }

  /**
   * Separate token bucket for the notification writes (backlog #309):
   * {@code POST /api/notifications/stream-ticket}, {@code PUT
   * /api/notifications/preferences/{type}}, {@code POST
   * /api/notifications/{id}/read}, {@code POST
   * /api/notifications/read-all} and {@code DELETE
   * /api/notifications/{id}} share it (default 20 writes/minute per
   * authenticated user, like the #282/#308 buckets). Keyed on the JWT
   * principal id like the social-graph bucket — the abuse surface is
   * one account churning its own notification surface, not an IP —
   * and it neither consumes nor starves the #308 graph bucket or any
   * neighbouring write bucket. The SSE stream GET and the
   * notification list / preferences-catalogue / unread-count GETs are
   * reads and stay unthrottled. Never resets on success: a minted
   * ticket, a toggled preference or a cleared notification must not
   * refill the allowance.
   */
  @Bean
  public AuthRateLimiter notificationRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties notificationProps = new AuthRateLimitProperties();
    notificationProps.setEnabled(props.isEnabled());
    notificationProps.setAttemptsPerMinute(props.getNotificationAttemptsPerMinute());
    return new AuthRateLimiter(clock, notificationProps);
  }

  /**
   * Separate token bucket for the account-identity writes (backlog #310):
   * {@code POST /api/auth/username} and {@code DELETE /api/auth/me} share
   * it (default 5 writes/minute per authenticated user, like the
   * credential buckets). Keyed on the JWT principal id like the
   * notification bucket — renaming or closing an account is a
   * per-account abuse surface, and keying by IP would let one NAT
   * address starve every user behind it. Deliberately separate from
   * the #290 password-change bucket (changing the password stays there)
   * and the #296 session-revocation bucket (killing sessions stays
   * there), so identity traffic can never starve them, nor vice versa.
   * Never resets on success: a completed rename must not refill the
   * allowance, and a throttled closure attempt never reaches the
   * controller, so it closes nothing.
   */
  @Bean
  public AuthRateLimiter accountIdentityRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties identityProps = new AuthRateLimitProperties();
    identityProps.setEnabled(props.isEnabled());
    identityProps.setAttemptsPerMinute(props.getAccountIdentityAttemptsPerMinute());
    return new AuthRateLimiter(clock, identityProps);
  }

  /**
   * Separate token bucket for the offer lifecycle transitions
   * ({@code POST /api/offers/{id}/accept}, {@code /decline},
   * {@code /counter}, {@code /counter-accept}, {@code /counter-decline}
   * and {@code /withdraw}, backlog #293; default 20 transitions/minute
   * per authenticated user). The six shapes share this one bucket —
   * they are the negotiation toward or away from a paid order —
   * keyed on the JWT principal id like the social-write bucket, so
   * lifecycle traffic neither consumes nor starves the #282 creation
   * bucket (filing an offer stays there) or the #228 order-transition
   * bucket. Never resets on success.
   */
  @Bean
  public AuthRateLimiter offerLifecycleRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties lifecycleProps = new AuthRateLimitProperties();
    lifecycleProps.setEnabled(props.isEnabled());
    lifecycleProps.setAttemptsPerMinute(props.getOfferLifecycleAttemptsPerMinute());
    return new AuthRateLimiter(clock, lifecycleProps);
  }

  /**
   * Separate token bucket for the passkey challenge-issuance and
   * enrolment-verification shapes (backlog #295):
   * {@code POST /api/auth/2fa/passkeys/registration/options},
   * {@code POST /api/auth/2fa/passkeys/registration/verify} and
   * {@code POST /api/auth/2fa/passkeys/assertion/options} share it
   * (default 5 attempts/minute per authenticated user, like the
   * step-up bucket). Keyed on the JWT principal id like the
   * step-up/password-change buckets — challenge minting is a
   * per-account abuse surface, and keying by IP would let one NAT
   * address starve every enrolling user behind it; the public
   * assertion-options shape falls back to the per-IP key, since its
   * caller holds no Bearer token yet. Deliberately separate from
   * the TOTP authenticate bucket (backlog #101), which keeps the
   * {@code /2fa/authenticate} exchange itself. Never reset on
   * success: a completed enrolment must not refill the allowance.
   */
  @Bean
  public AuthRateLimiter passkeyRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties passkeyProps = new AuthRateLimitProperties();
    passkeyProps.setEnabled(props.isEnabled());
    passkeyProps.setAttemptsPerMinute(props.getPasskeyAttemptsPerMinute());
    return new AuthRateLimiter(clock, passkeyProps);
  }

  /**
   * Separate token bucket for the session and trusted-device revocation
   * writes (backlog #296): {@code DELETE /api/auth/sessions/{id}},
   * {@code POST /api/auth/sessions/revoke-others} and {@code DELETE
   * /api/auth/trusted-devices/{id}} share it (default 5 writes/minute
   * per authenticated user, like the step-up bucket). Keyed on the JWT
   * principal id like the step-up/password-change buckets — session
   * killing is a per-account abuse surface, and keying by IP would let
   * one NAT address starve every user behind it. Deliberately separate
   * from the step-up bucket (revoke-others is step-up gated, but the
   * gate proves the proof is fresh, not that the rate is sane) and the
   * credential bucket, so revocation traffic can never starve logins
   * or re-proofs, nor vice versa. Never reset on success: a completed
   * revoke must not refill the allowance.
   */
  @Bean
  public AuthRateLimiter sessionRevocationRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties revocationProps = new AuthRateLimitProperties();
    revocationProps.setEnabled(props.isEnabled());
    revocationProps.setAttemptsPerMinute(props.getSessionRevocationAttemptsPerMinute());
    return new AuthRateLimiter(clock, revocationProps);
  }

  /**
   * Separate token bucket for the ADMIN dispute/appeal resolutions
   * (backlog #297): {@code POST /api/admin/disputes/{id}/resolve-refund},
   * {@code POST /api/admin/disputes/{id}/resolve-release}, {@code POST
   * /api/admin/appeals/{id}/uphold} and {@code POST
   * /api/admin/appeals/{id}/reject} share it (default 20
   * resolutions/minute per authenticated ADMIN, like the #282
   * bucket). Keyed on the JWT principal id like the other per-user
   * buckets — one ADMIN clearing a queue is legitimate traffic, and
   * keying by IP would let one NAT address starve every ADMIN behind
   * it; non-ADMIN callers land on their own principal key, so their
   * traffic can never starve an ADMIN's bucket, nor vice versa.
   * Deliberately separate from the #282 creation bucket (filing a
   * dispute or an appeal stays there) and every other bucket, so
   * resolution traffic can never starve them, nor vice versa. Never
   * reset on success: a completed resolution must not refill the
   * allowance.
   */
  @Bean
  public AuthRateLimiter adminResolutionRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties resolutionProps = new AuthRateLimitProperties();
    resolutionProps.setEnabled(props.isEnabled());
    resolutionProps.setAttemptsPerMinute(props.getAdminResolutionAttemptsPerMinute());
    return new AuthRateLimiter(clock, resolutionProps);
  }

  /**
   * Separate token bucket for review edits and seller responses
   * ({@code PUT /api/reviews/{id}} and {@code PUT
   * /api/reviews/{id}/response}, backlog #298; default 20
   * edits/responses per minute per authenticated user, like the #282
   * bucket). Keyed on the JWT principal id like the other per-user
   * buckets — one account rewriting reviews is legitimate traffic at
   * human pace, and keying by IP would let one NAT address starve
   * every user behind it. Deliberately separate from the #282
   * creation bucket (filing a review stays there), so edit/response
   * traffic can never starve creation traffic, nor vice versa. Never
   * reset on success: a completed edit must not refill the allowance.
   */
  @Bean
  public AuthRateLimiter reviewEditRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties reviewEditProps = new AuthRateLimitProperties();
    reviewEditProps.setEnabled(props.isEnabled());
    reviewEditProps.setAttemptsPerMinute(props.getReviewEditAttemptsPerMinute());
    return new AuthRateLimiter(clock, reviewEditProps);
  }

  /**
   * Separate token bucket for the TOTP lifecycle writes (backlog #301):
   * {@code POST /api/auth/2fa/setup}, {@code POST /api/auth/2fa/enable},
   * {@code POST /api/auth/2fa/disable} and {@code POST
   * /api/auth/2fa/recovery-codes/regenerate} share it (default 5
   * writes/minute per authenticated user, like the step-up bucket).
   * Keyed on the JWT principal id like the step-up/password-change
   * buckets — enrolling or stripping a second factor is a per-account
   * abuse surface, and keying by IP would let one NAT address starve
   * every user behind it. Deliberately separate from the TOTP
   * authenticate bucket (backlog #101), which keeps the exchange
   * itself, and from the passkey bucket (backlog #295), so lifecycle
   * traffic can never starve either, nor vice versa. Never reset on
   * success: a completed enrolment must not refill the allowance.
   */
  @Bean
  public AuthRateLimiter totpSetupRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties setupProps = new AuthRateLimitProperties();
    setupProps.setEnabled(props.isEnabled());
    setupProps.setAttemptsPerMinute(props.getTotpSetupAttemptsPerMinute());
    return new AuthRateLimiter(clock, setupProps);
  }

  /**
   * Separate token bucket for passkey management writes (backlog #302):
   * {@code DELETE /api/auth/2fa/passkeys/{id}} and {@code PATCH
   * /api/auth/2fa/passkeys/{id}} share it (default 20 writes/minute
   * per authenticated user, like the #282 bucket). Keyed on the JWT
   * principal id like the step-up/password-change buckets — deleting
   * or relabelling a second-factor credential is a per-account abuse
   * surface, and keying by IP would let one NAT address starve every
   * user behind it. Deliberately separate from the passkey bucket
   * (backlog #295), which keeps challenge issuance and enrolment
   * verification, and from the TOTP lifecycle bucket (backlog #301),
   * so management traffic can never starve either, nor vice versa.
   * Never reset on success: a completed delete or rename must not
   * refill the allowance.
   */
  @Bean
  public AuthRateLimiter passkeyManagementRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties managementProps = new AuthRateLimitProperties();
    managementProps.setEnabled(props.isEnabled());
    managementProps.setAttemptsPerMinute(props.getPasskeyManagementAttemptsPerMinute());
    return new AuthRateLimiter(clock, managementProps);
  }

  /**
   * Separate token bucket for the ADMIN user actions (backlog #303):
   * {@code POST /api/admin/users/{id}/disable}, {@code POST
   * /api/admin/users/{id}/enable} and {@code POST
   * /api/admin/users/{id}/totp-reset} share it (default 20
   * actions/minute per authenticated ADMIN, like the #297 bucket).
   * Keyed on the JWT principal id like the other per-user buckets —
   * one ADMIN acting on accounts at volume is a per-account abuse
   * surface, and keying by IP would let one NAT address starve every
   * ADMIN behind it; non-ADMIN callers land on their own principal
   * key, so their traffic can never starve an ADMIN's bucket.
   * Deliberately separate from the #297 dispute/appeal resolution
   * bucket (case resolution is a different abuse shape) and every
   * other bucket, so account-action traffic can never starve them,
   * nor vice versa. Never reset on success: a completed disable,
   * enable or TOTP reset must not refill the allowance.
   */
  @Bean
  public AuthRateLimiter adminUserActionRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties actionProps = new AuthRateLimitProperties();
    actionProps.setEnabled(props.isEnabled());
    actionProps.setAttemptsPerMinute(props.getAdminUserActionAttemptsPerMinute());
    return new AuthRateLimiter(clock, actionProps);
  }

  /**
   * Separate token bucket for the ADMIN report dismiss/action writes
   * (backlog #304): {@code POST /api/admin/reports/{id}/dismiss} and
   * {@code POST /api/admin/reports/{id}/action} share it (default 20
   * actions/minute per authenticated ADMIN, like the #297/#303
   * buckets). Keyed on the JWT principal id like the other per-user
   * buckets — one ADMIN clearing the listing-report queue is
   * legitimate traffic, and keying by IP would let one NAT address
   * starve every ADMIN behind it; non-ADMIN callers land on their
   * own principal key, so their traffic can never starve an ADMIN's
   * bucket. Deliberately separate from the #297 dispute/appeal
   * resolution bucket (a different moderation queue) and the #303
   * ADMIN user-action bucket, so report-action traffic can never
   * starve them, nor vice versa. Never reset on success: a
   * completed dismiss or action must not refill the allowance.
   */
  @Bean
  public AuthRateLimiter adminReportActionRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties actionProps = new AuthRateLimitProperties();
    actionProps.setEnabled(props.isEnabled());
    actionProps.setAttemptsPerMinute(props.getAdminReportActionAttemptsPerMinute());
    return new AuthRateLimiter(clock, actionProps);
  }

  /**
   * Separate token bucket for the ADMIN payment webhook replay
   * (backlog #305): {@code POST /api/admin/payments/webhooks/{id}/replay}
   * (default 20 replays/minute per authenticated ADMIN, like the
   * #297/#303/#304 buckets). Keyed on the JWT principal id like the
   * other per-user buckets — one ADMIN replaying stuck events is
   * legitimate traffic, and keying by IP would let one NAT address
   * starve every ADMIN behind it; non-ADMIN callers land on their
   * own principal key, so their traffic can never starve an ADMIN's
   * bucket. Deliberately separate from the #297 dispute/appeal
   * resolution bucket (a replay is not a case resolution), the #303
   * ADMIN user-action bucket and the #304 ADMIN report-action bucket,
   * so replay traffic can never starve them, nor vice versa. Never
   * reset on success: a completed replay must not refill the
   * allowance.
   */
  @Bean
  public AuthRateLimiter adminWebhookReplayRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties replayProps = new AuthRateLimitProperties();
    replayProps.setEnabled(props.isEnabled());
    replayProps.setAttemptsPerMinute(props.getAdminWebhookReplayAttemptsPerMinute());
    return new AuthRateLimiter(clock, replayProps);
  }

  /**
   * Separate token bucket for the ADMIN category writes (backlog
   * #312): {@code POST /api/categories}, {@code PATCH
   * /api/categories/{id}} and {@code DELETE /api/categories/{id}}
   * share it (default 20 writes/minute per authenticated ADMIN,
   * like the #297/#303/#304/#305 buckets). Keyed on the JWT
   * principal id like the other per-user buckets — one ADMIN
   * reshaping the taxonomy at volume is a per-account abuse
   * surface, and keying by IP would let one NAT address starve
   * every ADMIN behind it; non-ADMIN callers land on their own
   * principal key, so their traffic can never starve an ADMIN's
   * bucket. Deliberately separate from the #297 dispute/appeal
   * resolution bucket, the #303 ADMIN user-action bucket, the #304
   * ADMIN report-action bucket and the #305 ADMIN webhook-replay
   * bucket, so category traffic can never starve them, nor vice
   * versa. Never reset on success: a completed create, rename or
   * delete must not refill the allowance.
   */
  @Bean
  public AuthRateLimiter adminCategoryRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties categoryProps = new AuthRateLimitProperties();
    categoryProps.setEnabled(props.isEnabled());
    categoryProps.setAttemptsPerMinute(props.getAdminCategoryAttemptsPerMinute());
    return new AuthRateLimiter(clock, categoryProps);
  }

  /**
   * Separate token bucket for the saved-search writes (backlog
   * #313): {@code POST /api/users/me/saved-searches} and {@code
   * DELETE /api/users/me/saved-searches/{id}} share it (default 20
   * writes/minute per authenticated user, like the #282 bucket).
   * Keyed on the JWT principal id like the other per-user buckets —
   * the abuse surface is one account saving or deleting searches at
   * volume, not an IP. Deliberately separate from the #308
   * social-graph bucket (saving a listing vs saving a search are
   * different surfaces despite the shared "save" word), so
   * saved-search traffic can neither consume nor be starved by graph
   * traffic, nor vice versa. Never resets on success: a completed
   * save or delete must not refill the allowance.
   */
  @Bean
  public AuthRateLimiter savedSearchRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties savedSearchProps = new AuthRateLimitProperties();
    savedSearchProps.setEnabled(props.isEnabled());
    savedSearchProps.setAttemptsPerMinute(props.getSavedSearchAttemptsPerMinute());
    return new AuthRateLimiter(clock, savedSearchProps);
  }

  /**
   * Separate token bucket for the ADMIN listing DELETE (backlog
   * #314): {@code DELETE /api/items/{id}} (default 20
   * deletes/minute per authenticated ADMIN, like the #312 ADMIN
   * category bucket). Keyed on the JWT principal id like the other
   * per-user buckets — one ADMIN deleting listings at volume is a
   * per-account abuse surface, and keying by IP would let one NAT
   * address starve every ADMIN behind it; non-ADMIN callers land on
   * their own principal key, so their traffic can never starve an
   * ADMIN's bucket. Deliberately separate from the #307
   * listing-edit bucket, so listing-delete traffic can neither
   * consume nor be starved by edit traffic, nor vice versa. Never
   * resets on success: a completed delete must not refill the
   * allowance.
   */
  @Bean
  public AuthRateLimiter adminListingDeleteRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties listingDeleteProps = new AuthRateLimitProperties();
    listingDeleteProps.setEnabled(props.isEnabled());
    listingDeleteProps.setAttemptsPerMinute(props.getAdminListingDeleteAttemptsPerMinute());
    return new AuthRateLimiter(clock, listingDeleteProps);
  }

  /**
   * Separate token bucket for the dispute-evidence DELETE (backlog
   * #315): {@code DELETE /api/disputes/{disputeId}/evidence/{evidenceId}}
   * (default 20 deletes/minute per authenticated user, like the #282
   * bucket). Keyed on the JWT principal id like the other per-user
   * buckets — one account removing evidence photos at volume is a
   * per-account abuse surface, and keying by IP would let one NAT
   * address starve every disputant behind it. Deliberately separate
   * from the #282 social-write bucket (filing evidence with the POST
   * sibling stays there), so delete traffic can neither consume nor
   * be starved by filing traffic, nor vice versa. Never resets on
   * success: a completed removal must not refill the allowance, and
   * a throttled delete never reaches the controller, so it deletes
   * neither the row nor the file.
   */
  @Bean
  public AuthRateLimiter disputeEvidenceDeleteRateLimiter(Clock clock,
                                                          AuthRateLimitProperties props) {
    AuthRateLimitProperties evidenceDeleteProps = new AuthRateLimitProperties();
    evidenceDeleteProps.setEnabled(props.isEnabled());
    evidenceDeleteProps.setAttemptsPerMinute(props.getDisputeEvidenceDeleteAttemptsPerMinute());
    return new AuthRateLimiter(clock, evidenceDeleteProps);
  }

  /**
   * Separate token bucket for logout (backlog #316):
   * {@code POST /api/auth/logout} (default 20 logouts/minute per
   * authenticated user, like the #282 bucket). Keyed on the JWT
   * principal id like the other per-user buckets — a burst of
   * logouts is session-kill spam by one account, and keying by IP
   * would let one NAT address starve every user behind it.
   * Deliberately separate from the credential bucket (login and
   * refresh stay there) and from the #296 session-revocation bucket
   * (revoke-others stays there), so logout traffic can neither
   * consume nor be starved by either, nor vice versa. Never resets
   * on success: a completed logout must not refill the allowance,
   * and a throttled logout never reaches the controller, so it
   * revokes nothing and bumps no token version.
   */
  @Bean
  public AuthRateLimiter logoutRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties logoutProps = new AuthRateLimitProperties();
    logoutProps.setEnabled(props.isEnabled());
    logoutProps.setAttemptsPerMinute(props.getLogoutAttemptsPerMinute());
    return new AuthRateLimiter(clock, logoutProps);
  }

  /**
   * Separate token bucket for the public mock-PSP webhook receiver
   * (backlog #306): {@code POST /api/payments/webhooks/mock-psp}
   * (default 60 deliveries/minute per client IP, like the #204 CSP
   * and #223 telemetry buckets). The endpoint is public by necessity
   * (a PSP callback holds no user session), so this bucket is the
   * flood gate in front of the HMAC signature check: it must not
   * share any credential bucket, and delivery traffic must not
   * starve logins. Deliberately separate from the #305 ADMIN replay
   * bucket (an authenticated ADMIN action on a stored event, not an
   * inbound PSP delivery). Never reset on success: an accepted
   * delivery must not refill the allowance.
   */
  @Bean
  public AuthRateLimiter mockPspWebhookRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties webhookProps = new AuthRateLimitProperties();
    webhookProps.setEnabled(props.isEnabled());
    webhookProps.setAttemptsPerMinute(props.getMockPspWebhookAttemptsPerMinute());
    return new AuthRateLimiter(clock, webhookProps);
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
