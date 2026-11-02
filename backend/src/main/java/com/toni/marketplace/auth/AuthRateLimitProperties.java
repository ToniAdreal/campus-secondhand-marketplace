package com.toni.marketplace.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Throttle policy for the credential endpoints ({@code POST /api/auth/login}
 * and {@code POST /api/auth/register}), for the refresh endpoint
 * ({@code POST /api/auth/refresh}), and for message sends
 * ({@code POST /api/messages}).
 *
 * <p>Defaults: enabled, 5 attempts per minute per client IP on the credential
 * endpoints, 30 per minute on the refresh endpoint (its own, more generous
 * bucket — see {@link #getRefreshAttemptsPerMinute}), and 30 sends per minute
 * per authenticated user on the message endpoint (see
 * {@link #getMessageAttemptsPerMinute}). Override with
 * e.g. {@code app.auth.rate-limit.attempts-per-minute=10}. The shared test
 * profile disables the limiter ({@code src/test/resources/application.properties})
 * because the suite hammers these endpoints from a single test IP; the
 * dedicated {@code AuthRateLimitTest} / {@code AuthRefreshRateLimitTest} /
 * {@code MessageRateLimitTest} re-enable it explicitly.
 */
@ConfigurationProperties(prefix = "app.auth.rate-limit")
public class AuthRateLimitProperties {

  /** Master switch; when false the filter passes every request through. */
  private boolean enabled = true;

  /**
   * Token-bucket size and sustained refill rate per minute per client IP.
   * Must be &ge; 1.
   */
  private int attemptsPerMinute = 5;

  /**
   * Token-bucket size and sustained refill rate per minute per client IP for
   * {@code POST /api/auth/refresh} — the unauthenticated token-guessing
   * surface. Higher than the credential ceiling on purpose: legitimate
   * multi-tab clients refresh routinely (a 5/min ceiling would log users out
   * on burst tab restores), so the limit exists only to stop sustained
   * brute force, not to gate normal use. Must be &ge; 1.
   */
  private int refreshAttemptsPerMinute = 30;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public int getAttemptsPerMinute() {
    return attemptsPerMinute;
  }

  public void setAttemptsPerMinute(int attemptsPerMinute) {
    this.attemptsPerMinute = attemptsPerMinute;
  }

  public int getRefreshAttemptsPerMinute() {
    return refreshAttemptsPerMinute;
  }

  public void setRefreshAttemptsPerMinute(int refreshAttemptsPerMinute) {
    this.refreshAttemptsPerMinute = refreshAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per
   * <em>authenticated user</em> (JWT principal id) for
   * {@code POST /api/messages}. Keyed on the user rather than the IP: the
   * endpoint is authenticated, so a dorm full of users behind one NAT
   * address must not share a single bucket. Unauthenticated/unsigned
   * requests (which the security chain will 401 anyway) fall back to a
   * per-IP bucket so anonymous hammering is still throttled. Must be
   * &ge; 1.
   */
  private int messageAttemptsPerMinute = 30;

  public int getMessageAttemptsPerMinute() {
    return messageAttemptsPerMinute;
  }

  public void setMessageAttemptsPerMinute(int messageAttemptsPerMinute) {
    this.messageAttemptsPerMinute = messageAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per client IP
   * for the password-reset endpoints (backlog #90):
   * {@code POST /api/auth/password-reset} (a mail-bombing surface) and
   * {@code POST /api/auth/password-reset/confirm} (a token-guessing
   * surface) share this one bucket. Sized like the credential bucket
   * (5/min): a legitimate user requests one reset and confirms once, so
   * the ceiling only bites automation. Must be &ge; 1.
   */
  private int passwordResetAttemptsPerMinute = 5;

  public int getPasswordResetAttemptsPerMinute() {
    return passwordResetAttemptsPerMinute;
  }

  public void setPasswordResetAttemptsPerMinute(int passwordResetAttemptsPerMinute) {
    this.passwordResetAttemptsPerMinute = passwordResetAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per client IP
   * for the second-factor exchange {@code POST /api/auth/2fa/authenticate}
   * (backlog #101). The challenge is a 5-minute Bearer <redacted> for a
   * 6-digit code, so this endpoint is an online-guessing surface; sized
   * like the credential bucket (5/min) — a legitimate exchange is a
   * single attempt, so the ceiling only bites automation. It has its
   * own bucket: TOTP traffic must neither consume nor be starved by the
   * credential bucket (and the tests pin that a login from the same IP
   * still works when this bucket is empty). Unlike the credential
   * bucket, a successful exchange does NOT reset it — resetting on
   * success would let anyone holding one valid challenge+code refill a
   * guesser's budget. Must be &ge; 1.
   */
  private int totpAttemptsPerMinute = 5;

  public int getTotpAttemptsPerMinute() {
    return totpAttemptsPerMinute;
  }

  public void setTotpAttemptsPerMinute(int totpAttemptsPerMinute) {
    this.totpAttemptsPerMinute = totpAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per client IP
   * for the email-verification endpoints (backlog #138):
   * {@code POST /api/auth/email-verification/resend} (a mail-bombing
   * surface) and {@code POST /api/auth/email-verification/confirm} (a
   * token-guessing surface) share this one bucket. Sized like the
   * credential bucket (5/min) — a legitimate user resends once and
   * confirms once, so the ceiling only bites automation. Its own bucket,
   * on the backlog #101 pattern: verification traffic must neither
   * consume nor be starved by the credential bucket, and a successful
   * confirm never resets it (a completed verification must not refill a
   * guesser's budget). Must be &ge; 1.
   */
  private int emailVerificationAttemptsPerMinute = 5;

  public int getEmailVerificationAttemptsPerMinute() {
    return emailVerificationAttemptsPerMinute;
  }

  public void setEmailVerificationAttemptsPerMinute(int emailVerificationAttemptsPerMinute) {
    this.emailVerificationAttemptsPerMinute = emailVerificationAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per client IP
   * for the email-change REQUEST endpoint (backlog #294):
   * {@code POST /api/auth/email-change} (a mail-bombing surface — each
   * accepted request mails a token to the NEW address). It gets its OWN
   * bucket, deliberately separate from the email-verification bucket
   * above: the confirm endpoint ({@code POST
   * /api/auth/email-change/confirm}) stays in that bucket, so request
   * traffic must neither consume nor be starved by confirm traffic,
   * nor vice versa. Sized like the verification bucket (5/min) — a
   * legitimate user stages one change at a time, so the ceiling only
   * bites automation. Like the other non-credential buckets it never
   * resets on success — a staged change must not refill the
   * mail-bombing allowance. Must be &ge; 1.
   */
  private int emailChangeRequestAttemptsPerMinute = 5;

  public int getEmailChangeRequestAttemptsPerMinute() {
    return emailChangeRequestAttemptsPerMinute;
  }

  public void setEmailChangeRequestAttemptsPerMinute(int emailChangeRequestAttemptsPerMinute) {
    this.emailChangeRequestAttemptsPerMinute = emailChangeRequestAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per
   * <em>authenticated user</em> (JWT principal id) for listing reports
   * ({@code POST /api/items/{id}/report}, backlog #160). Keyed on the
   * user rather than the IP, mirroring the message bucket: the endpoint
   * is authenticated, so one spammer behind a NAT address must not
   * starve everyone else (and anonymous requests, which the security
   * chain 401s anyway, fall back to a per-IP bucket). Sized below the
   * message bucket (10/min): a legitimate user reports a handful of
   * listings, so the ceiling only bites automation flooding the ADMIN
   * moderation queue. Like the message bucket it never resets on
   * success — a filed report must not refill the spam allowance.
   * Must be &ge; 1.
   */
  private int reportAttemptsPerMinute = 10;

  public int getReportAttemptsPerMinute() {
    return reportAttemptsPerMinute;
  }

  public void setReportAttemptsPerMinute(int reportAttemptsPerMinute) {
    this.reportAttemptsPerMinute = reportAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per
   * <em>authenticated user</em> (JWT principal id) for the account data
   * export ({@code GET /api/users/me/export}, backlog #172). Keyed on
   * the user rather than the IP, mirroring the message/report buckets
   * (anonymous requests, which the security chain 401s anyway, fall
   * back to a per-IP bucket). Sized at 5/min: the export is the
   * heaviest read in the app — every section of the caller's data in
   * one document — so the ceiling is deliberately the tightest of the
   * per-user buckets, while a legitimate user downloading their data
   * a few times in a row never meets it. Like the other usage buckets
   * it never resets on success — a completed export must not refill
   * the allowance. Must be &ge; 1.
   */
  private int dataExportAttemptsPerMinute = 5;

  public int getDataExportAttemptsPerMinute() {
    return dataExportAttemptsPerMinute;
  }

  public void setDataExportAttemptsPerMinute(int dataExportAttemptsPerMinute) {
    this.dataExportAttemptsPerMinute = dataExportAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per
   * authenticated user for the step-up re-proof endpoint ({@code POST
   * /api/auth/step-up}, backlog #218). The endpoint verifies the
   * current password, so it is a password-guessing surface for anyone
   * holding a stolen Bearer token — it gets its own per-user bucket
   * (JWT principal, per-IP fallback like the message/export buckets)
   * that never resets on success. Must be &ge; 1.
   */
  private int stepUpAttemptsPerMinute = 5;

  public int getStepUpAttemptsPerMinute() {
    return stepUpAttemptsPerMinute;
  }

  public void setStepUpAttemptsPerMinute(int stepUpAttemptsPerMinute) {
    this.stepUpAttemptsPerMinute = stepUpAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per
   * authenticated user for the password-change endpoint ({@code POST
   * /api/auth/password}, backlog #290). The endpoint verifies the
   * current password, so it is a password-guessing surface for anyone
   * holding a stolen Bearer token — it gets its own per-user bucket
   * (JWT principal, per-IP fallback like the message/export buckets)
   * that never resets on success, sized like the step-up bucket's
   * 5/min: a completed change must not refill a guesser's budget.
   * Deliberately separate from the step-up bucket (which covers only
   * the re-proof) and the credential bucket, so guessing at one
   * surface can never starve the others. Must be &ge; 1.
   */
  private int passwordChangeAttemptsPerMinute = 5;

  public int getPasswordChangeAttemptsPerMinute() {
    return passwordChangeAttemptsPerMinute;
  }

  public void setPasswordChangeAttemptsPerMinute(int passwordChangeAttemptsPerMinute) {
    this.passwordChangeAttemptsPerMinute = passwordChangeAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per client IP
   * for the CSP violation report intake ({@code POST
   * /api/security/csp-report}, backlog #204). Keyed on the IP: the
   * endpoint is public by necessity (browsers send reports without a
   * session), so there is no principal to key on — the trusted-proxy
   * resolution in {@code AuthRateLimitFilter} applies as for the other
   * per-IP buckets. Sized generously (60/min): reports are
   * browser-generated and bursty (one broken deploy can make every open
   * page report at once), so the ceiling exists to stop a deliberate
   * flood of the log/metric pipeline, not to gate normal reporting.
   * Like the other non-credential buckets it never resets on success —
   * an accepted report must not refill the allowance. Must be &ge; 1.
   */
  private int cspReportAttemptsPerMinute = 60;

  public int getCspReportAttemptsPerMinute() {
    return cspReportAttemptsPerMinute;
  }

  public void setCspReportAttemptsPerMinute(int cspReportAttemptsPerMinute) {
    this.cspReportAttemptsPerMinute = cspReportAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per
   * <em>authenticated user</em> (JWT principal id) for listing creation
   * ({@code POST /api/items}, backlog #219). Keyed on the user rather
   * than the IP, mirroring the message/report buckets: the endpoint is
   * authenticated, so one listing spammer behind a NAT address must not
   * starve every seller behind it (anonymous requests, which the
   * security chain 401s anyway, fall back to a per-IP bucket). Sized at
   * 10/min: generous for a human listing their shelf, useless for a
   * listing-spam script. Like the other usage buckets it never resets
   * on success — a created listing must not refill the spam allowance.
   * Must be &ge; 1.
   */
  private int listingCreateAttemptsPerMinute = 10;

  public int getListingCreateAttemptsPerMinute() {
    return listingCreateAttemptsPerMinute;
  }

  public void setListingCreateAttemptsPerMinute(int listingCreateAttemptsPerMinute) {
    this.listingCreateAttemptsPerMinute = listingCreateAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per
   * <em>authenticated user</em> (JWT principal id) for order creation
   * ({@code POST /api/orders}, backlog #219). Same keying as the
   * listing-creation bucket above: the abuse surface is an account
   * hammering order rows (and triggering downstream
   * idempotency/notification work), not an IP. Sized at 20/min —
   * higher than the listing bucket because the buy flow is one order
   * per click and a fast buyer is legitimate, while a script ordering
   * at volume is the throttle target. Never resets on success, like
   * every other non-credential bucket. Must be &ge; 1.
   */
  private int orderCreateAttemptsPerMinute = 20;

  public int getOrderCreateAttemptsPerMinute() {
    return orderCreateAttemptsPerMinute;
  }

  public void setOrderCreateAttemptsPerMinute(int orderCreateAttemptsPerMinute) {
    this.orderCreateAttemptsPerMinute = orderCreateAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per client IP
   * for the frontend error telemetry intake ({@code POST
   * /api/telemetry/errors}, backlog #223). Keyed on the IP: the
   * endpoint is public by necessity (a crash can happen on an
   * anonymous page), so there is no principal to key on — the
   * trusted-proxy resolution in {@code AuthRateLimitFilter} applies as
   * for the other per-IP buckets. Sized generously (60/min, like the
   * CSP bucket): a crash loop in one tab can otherwise flood the
   * log/metric pipeline, but a broken release makes every open page
   * beacon at once, so the ceiling exists to stop a deliberate flood,
   * not to gate normal reporting. Like the other non-credential buckets
   * it never resets on success. Must be &ge; 1.
   */
  private int telemetryErrorAttemptsPerMinute = 60;

  public int getTelemetryErrorAttemptsPerMinute() {
    return telemetryErrorAttemptsPerMinute;
  }

  public void setTelemetryErrorAttemptsPerMinute(int telemetryErrorAttemptsPerMinute) {
    this.telemetryErrorAttemptsPerMinute = telemetryErrorAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per
   * <em>authenticated user</em> (JWT principal id) for the order
   * transition POSTs ({@code POST /api/orders/{id}/pay}, {@code /cancel},
   * {@code /complete}, {@code /refund}, {@code /ship}, backlog #228).
   * Same keying as the listing/order-create buckets (anonymous requests,
   * which the security chain 401s anyway, fall back to a per-IP bucket).
   * The five transitions share this one bucket: they are the money path
   * of a single order, so one account hammering transitions — paid or
   * not — is one abuse shape, and the transitions must neither consume
   * nor be starved by the order-create bucket. Sized at 20/min like
   * order creation: a legitimate checkout is a handful of transitions,
   * while a script flipping orders at volume is the throttle target.
   * Never resets on success — an idempotent re-pay must not refill the
   * allowance. Must be &ge; 1.
   */
  private int orderTransitionAttemptsPerMinute = 20;

  public int getOrderTransitionAttemptsPerMinute() {
    return orderTransitionAttemptsPerMinute;
  }

  public void setOrderTransitionAttemptsPerMinute(int orderTransitionAttemptsPerMinute) {
    this.orderTransitionAttemptsPerMinute = orderTransitionAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per
   * <em>authenticated user</em> (JWT principal id) for the order-tail
   * POSTs ({@code POST /api/orders/{id}/confirm-receipt} and
   * {@code POST /api/orders/{id}/handover-code/regenerate}, backlog
   * #289). Same keying as the order-transition bucket (per-IP fallback
   * for anonymous requests). The two shapes share this one bucket: they
   * are the tail of the same money path — receipt confirmation moves an
   * order toward COMPLETED and a regenerated handover code invalidates
   * the previous one — so one account hammering either is one abuse
   * shape, and the bucket must neither consume nor be starved by the
   * #228 transition bucket. Sized at 20/min like the transition bucket:
   * legitimate traffic is a handful per order (one confirm, at most a
   * couple of regenerates), so the ceiling only bites automation.
   * Never resets on success. Must be &ge; 1.
   */
  private int orderReceiptAttemptsPerMinute = 20;

  public int getOrderReceiptAttemptsPerMinute() {
    return orderReceiptAttemptsPerMinute;
  }

  public void setOrderReceiptAttemptsPerMinute(int orderReceiptAttemptsPerMinute) {
    this.orderReceiptAttemptsPerMinute = orderReceiptAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per
   * <em>authenticated user</em> (JWT principal id) for the item
   * mutation POSTs/PATCH ({@code POST /api/items/{id}/photo},
   * {@code /photos}, {@code /renew}, {@code /publish} and
   * {@code PATCH /api/items/{id}/status}, backlog #228). Same keying as
   * the other per-user buckets (per-IP fallback for anonymous requests).
   * The mutations share this one bucket: they are one account editing
   * its own listings, so they must neither consume nor be starved by
   * the listing-create bucket — and a photo-upload burst (a seller
   * adding a gallery one file at a time) is legitimate, so the ceiling
   * is sized generously at 30/min. Never resets on success. Must be
   * &ge; 1.
   */
  private int itemMutationAttemptsPerMinute = 30;

  public int getItemMutationAttemptsPerMinute() {
    return itemMutationAttemptsPerMinute;
  }

  public void setItemMutationAttemptsPerMinute(int itemMutationAttemptsPerMinute) {
    this.itemMutationAttemptsPerMinute = itemMutationAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per
   * <em>authenticated user</em> (JWT principal id) for the social /
   * money-adjacent creation writes (backlog #282): {@code POST
   * /api/items/{id}/offers}, {@code POST /api/orders/{id}/review},
   * {@code POST /api/orders/{id}/dispute}, {@code POST
   * /api/disputes/{id}/evidence} and {@code POST
   * /api/seller/reports/{id}/appeal} share this one bucket. Same
   * keying as the other per-user buckets (per-IP fallback for
   * anonymous requests). The five shapes share one bucket because
   * they are one abuse shape — one account flooding the
   * negotiation, review, dispute and appeal surfaces — and the
   * bucket must neither consume nor be starved by the neighbouring
   * write buckets (messages, reports, listing/order creation,
   * order transitions, item mutations). Sized at 20/min: a
   * legitimate user files a handful of these writes per minute
   * (an evidence burst is a few photos), while a script flooding
   * offers or disputes at volume is the throttle target. Never
   * resets on success. Must be &ge; 1.
   */
  private int socialWriteAttemptsPerMinute = 20;

  public int getSocialWriteAttemptsPerMinute() {
    return socialWriteAttemptsPerMinute;
  }

  public void setSocialWriteAttemptsPerMinute(int socialWriteAttemptsPerMinute) {
    this.socialWriteAttemptsPerMinute = socialWriteAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per
   * <em>authenticated user</em> (JWT principal id) for the offer
   * lifecycle transitions (backlog #293): {@code POST
   * /api/offers/{id}/accept}, {@code /decline}, {@code /counter},
   * {@code /counter-accept}, {@code /counter-decline} and
   * {@code /withdraw} share this one bucket. Same keying as the other
   * per-user buckets (per-IP fallback for anonymous requests). The six
   * shapes share one bucket because they are one abuse shape — one
   * account hammering the negotiation toward (or away from) a paid
   * order — and the bucket must neither consume nor be starved by
   * the #282 creation bucket (offer filing stays there) or the #228
   * order-transition bucket. Sized at 20/min like the #282 bucket:
   * legitimate negotiation is a handful of transitions per offer,
   * while a script flipping offers at volume is the throttle target.
   * Never resets on success. Must be &ge; 1.
   */
  private int offerLifecycleAttemptsPerMinute = 20;

  public int getOfferLifecycleAttemptsPerMinute() {
    return offerLifecycleAttemptsPerMinute;
  }

  public void setOfferLifecycleAttemptsPerMinute(int offerLifecycleAttemptsPerMinute) {
    this.offerLifecycleAttemptsPerMinute = offerLifecycleAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per
   * <em>authenticated user</em> (JWT principal id) for the passkey
   * challenge-issuance and enrolment-verification shapes (backlog
   * #295): {@code POST /api/auth/2fa/passkeys/registration/options},
   * {@code POST /api/auth/2fa/passkeys/registration/verify} and
   * {@code POST /api/auth/2fa/passkeys/assertion/options} share this
   * one bucket. Same keying as the other per-user buckets (per-IP
   * fallback for anonymous requests) — which matters here because
   * the assertion-options shape is public by design (the caller is
   * not authenticated yet, so it lands on the per-IP fallback key
   * while the two registration shapes land on the JWT-principal key).
   * Sized like the step-up bucket (5/min): a legitimate enrolment is
   * one options call plus one verify, and a login-time assertion
   * ceremony is one options call, so the ceiling only bites
   * automation minting challenges. Deliberately separate from the
   * TOTP authenticate bucket (backlog #101): the exchange itself
   * ({@code POST /api/auth/2fa/authenticate}) stays in that bucket;
   * the 2FA setup/enable/disable endpoints and recovery-code
   * regeneration have their own #301 bucket and never shared this
   * one. Never resets on success. Must be &ge; 1.
   */
  private int passkeyAttemptsPerMinute = 5;

  public int getPasskeyAttemptsPerMinute() {
    return passkeyAttemptsPerMinute;
  }

  public void setPasskeyAttemptsPerMinute(int passkeyAttemptsPerMinute) {
    this.passkeyAttemptsPerMinute = passkeyAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per
   * <em>authenticated user</em> (JWT principal id) for the session and
   * trusted-device revocation writes (backlog #296): {@code DELETE
   * /api/auth/sessions/{id}}, {@code POST
   * /api/auth/sessions/revoke-others} and {@code DELETE
   * /api/auth/trusted-devices/{id}} share this one bucket. Same keying
   * as the other per-user buckets (per-IP fallback for anonymous
   * requests, which the security chain 401s anyway). The three shapes
   * share one bucket because they are one abuse shape — one account
   * killing sessions or device trust at volume — and the bucket must
   * neither consume nor be starved by the step-up bucket (revoke-others
   * is step-up gated, but the gate proves freshness, not rate) or the
   * credential bucket. Sized like the step-up bucket (5/min): a
   * legitimate user revokes one session or device at a time, so the
   * ceiling only bites automation. Never resets on success — a
   * completed revoke must not refill the allowance, and a throttled
   * attempt never reaches the controller, so it revokes nothing.
   * Must be &ge; 1.
   */
  private int sessionRevocationAttemptsPerMinute = 5;

  public int getSessionRevocationAttemptsPerMinute() {
    return sessionRevocationAttemptsPerMinute;
  }

  public void setSessionRevocationAttemptsPerMinute(int sessionRevocationAttemptsPerMinute) {
    this.sessionRevocationAttemptsPerMinute = sessionRevocationAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per
   * <em>authenticated user</em> (JWT principal id) for the ADMIN
   * dispute/appeal resolutions (backlog #297): {@code POST
   * /api/admin/disputes/{id}/resolve-refund}, {@code POST
   * /api/admin/disputes/{id}/resolve-release}, {@code POST
   * /api/admin/appeals/{id}/uphold} and {@code POST
   * /api/admin/appeals/{id}/reject} share this one bucket. Same
   * keying as the other per-user buckets (per-IP fallback for
   * anonymous requests, which the security chain 403s anyway —
   * ADMIN-only endpoints answer 403 to non-ADMIN callers). The
   * four shapes share one bucket because they are one abuse
   * shape — one ADMIN clearing (or hammering) the dispute and
   * appeal queues — and the bucket must neither consume nor be
   * starved by the #282 creation bucket (filing a dispute or an
   * appeal stays there) or any other bucket. Sized generously
   * at 20/min like the #282 bucket: a single ADMIN clearing a
   * queue is legitimate traffic (one resolution per click), so
   * the ceiling only bites automation. Never resets on success.
   * Must be &ge; 1.
   */
  private int adminResolutionAttemptsPerMinute = 20;

  public int getAdminResolutionAttemptsPerMinute() {
    return adminResolutionAttemptsPerMinute;
  }

  public void setAdminResolutionAttemptsPerMinute(int adminResolutionAttemptsPerMinute) {
    this.adminResolutionAttemptsPerMinute = adminResolutionAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per
   * <em>authenticated user</em> (JWT principal id) for the TOTP
   * lifecycle writes (backlog #301): {@code POST /api/auth/2fa/setup},
   * {@code POST /api/auth/2fa/enable}, {@code POST /api/auth/2fa/disable}
   * and {@code POST /api/auth/2fa/recovery-codes/regenerate} share this
   * one bucket. Same keying as the other per-user buckets (per-IP
   * fallback for anonymous requests, which the security chain 401s
   * anyway). The four shapes share one bucket because they are one
   * abuse shape — one account enrolling, rotating or stripping its
   * second factor at volume — and the bucket must neither consume nor
   * be starved by the #101 authenticate bucket (the exchange itself
   * stays there) or the #295 passkey bucket. Sized like the step-up
   * bucket (5/min): a legitimate user enrolls or rotates recovery
   * codes once, not in a burst, so the ceiling only bites automation.
   * Never resets on success. Must be &ge; 1.
   */
  private int totpSetupAttemptsPerMinute = 5;

  public int getTotpSetupAttemptsPerMinute() {
    return totpSetupAttemptsPerMinute;
  }

  public void setTotpSetupAttemptsPerMinute(int totpSetupAttemptsPerMinute) {
    this.totpSetupAttemptsPerMinute = totpSetupAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per
   * <em>authenticated user</em> (JWT principal id) for passkey
   * management writes (backlog #302): {@code DELETE
   * /api/auth/2fa/passkeys/{id}} and {@code PATCH
   * /api/auth/2fa/passkeys/{id}} share this one bucket. Same keying
   * as the other per-user buckets (per-IP fallback for anonymous
   * requests, which the security chain 401s anyway). The two shapes
   * share one bucket because they are one abuse shape — one account
   * deleting or relabelling its second-factor credentials at volume —
   * and the bucket must neither consume nor be starved by the #295
   * passkey bucket (challenge issuance and enrolment verification
   * stay there) or the #301 TOTP lifecycle bucket. Sized generously
   * at 20/min like the #282 bucket: renaming is a harmless single
   * action and bulk deletion is not legitimate traffic, so the
   * ceiling only bites automation. Never resets on success. Must be
   * &ge; 1.
   */
  private int passkeyManagementAttemptsPerMinute = 20;

  public int getPasskeyManagementAttemptsPerMinute() {
    return passkeyManagementAttemptsPerMinute;
  }

  public void setPasskeyManagementAttemptsPerMinute(int passkeyManagementAttemptsPerMinute) {
    this.passkeyManagementAttemptsPerMinute = passkeyManagementAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per
   * <em>authenticated ADMIN</em> (JWT principal id) for the ADMIN user
   * actions (backlog #303): {@code POST /api/admin/users/{id}/disable},
   * {@code POST /api/admin/users/{id}/enable} and {@code POST
   * /api/admin/users/{id}/totp-reset} share this one bucket. Same
   * keying as the other per-user buckets (per-IP fallback for
   * anonymous requests, which the security chain 401s anyway — a
   * non-ADMIN caller's single attempt still answers 403 from the
   * security chain, on that caller's own principal key, so it can
   * never starve an ADMIN's bucket). The three shapes share one
   * bucket because they are one abuse shape — one ADMIN acting on
   * accounts at volume — and the bucket must neither consume nor be
   * starved by the #297 dispute/appeal resolution bucket (case
   * resolution is a different abuse shape) or any other bucket.
   * Sized generously at 20/min like the #297 bucket: a single ADMIN
   * clearing a queue of abusive accounts is legitimate traffic (one
   * action per click), so the ceiling only bites automation. Never
   * resets on success. Must be &ge; 1.
   */
  private int adminUserActionAttemptsPerMinute = 20;

  public int getAdminUserActionAttemptsPerMinute() {
    return adminUserActionAttemptsPerMinute;
  }

  public void setAdminUserActionAttemptsPerMinute(int adminUserActionAttemptsPerMinute) {
    this.adminUserActionAttemptsPerMinute = adminUserActionAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per
   * <em>authenticated ADMIN</em> (JWT principal id) for the ADMIN
   * report dismiss/action writes (backlog #304): {@code POST
   * /api/admin/reports/{id}/dismiss} and {@code POST
   * /api/admin/reports/{id}/action} share this one bucket. Same
   * keying as the other per-user buckets (per-IP fallback for
   * anonymous requests, which the security chain 401s anyway — a
   * non-ADMIN caller's single attempt still answers 403 from the
   * security chain, on that caller's own principal key, so it can
   * never starve an ADMIN's bucket). The two shapes share one
   * bucket because they are one abuse shape — one ADMIN clearing
   * (or hammering) the listing-report moderation queue — and the
   * bucket must neither consume nor be starved by the #297
   * dispute/appeal resolution bucket (a different queue) or the
   * #303 ADMIN user-action bucket. Sized generously at 20/min
   * like the #297/#303 buckets: a single ADMIN clearing a queue
   * is legitimate traffic (one resolution per click), so the
   * ceiling only bites automation. Never resets on success.
   * Must be &ge; 1.
   */
  private int adminReportActionAttemptsPerMinute = 20;

  public int getAdminReportActionAttemptsPerMinute() {
    return adminReportActionAttemptsPerMinute;
  }

  public void setAdminReportActionAttemptsPerMinute(int adminReportActionAttemptsPerMinute) {
    this.adminReportActionAttemptsPerMinute = adminReportActionAttemptsPerMinute;
  }

  /**
   * Review edits and seller responses (backlog #298): PUT
   * /api/reviews/{id} and PUT /api/reviews/{id}/response share one
   * per-user bucket, sized like the #282 creation bucket (20/min) —
   * one account rewriting or answering reviews at volume is one
   * abuse shape, and a single legitimate edit or response per click
   * never comes close to the ceiling. Deliberately separate from the
   * #282 bucket (filing a review stays there). Never resets on
   * success. Must be &ge; 1.
   */
  private int reviewEditAttemptsPerMinute = 20;

  public int getReviewEditAttemptsPerMinute() {
    return reviewEditAttemptsPerMinute;
  }

  public void setReviewEditAttemptsPerMinute(int reviewEditAttemptsPerMinute) {
    this.reviewEditAttemptsPerMinute = reviewEditAttemptsPerMinute;
  }
}
