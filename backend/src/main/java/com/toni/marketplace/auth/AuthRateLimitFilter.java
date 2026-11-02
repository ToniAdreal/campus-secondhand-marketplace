package com.toni.marketplace.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toni.marketplace.common.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Throttles the auth brute-force surfaces per client IP:
 * {@code POST /api/auth/login} and {@code POST /api/auth/register} share one
 * token bucket (default 5 attempts/minute — see
 * {@link AuthRateLimitProperties}), while {@code POST /api/auth/refresh}
 * has its <em>own</em> bucket (default 30/minute). The refresh endpoint is
 * unauthenticated (cookie-presented) and is otherwise a token-guessing
 * surface; it needs throttling of its own, but must neither share the tight
 * credential bucket (legitimate multi-tab clients refresh routinely) nor
 * consume it.
 *
 * <p>The password-reset endpoints ({@code POST /api/auth/password-reset}
 * and {@code POST /api/auth/password-reset/confirm}, backlog #90) share a
 * fourth, per-IP bucket (default 5/minute): the request endpoint is a
 * mail-bombing surface and the confirm endpoint a token-guessing surface.
 *
 * <p>The email-verification endpoints ({@code POST
 * /api/auth/email-verification/resend} and {@code POST
 * /api/auth/email-verification/confirm}, backlog #138) share a sixth,
 * per-IP bucket (default 5/minute): the resend endpoint is a
 * mail-bombing surface and the confirm endpoint a token-guessing surface.
 *
 * <p>The email-change request ({@code POST /api/auth/email-change},
 * backlog #294) has its own per-IP bucket (default 5/minute): the
 * request endpoint is a mail-bombing surface — each accepted request
 * mails a token to the NEW address — and it previously rode
 * unthrottled while only its confirm sibling shared the verification
 * bucket above. The confirm endpoint stays where it is; the request
 * bucket is deliberately separate, so request traffic can neither
 * consume nor be starved by verification/confirm traffic, and a
 * staged change never resets it.
 *
 * <p>The second-factor exchange ({@code POST /api/auth/2fa/authenticate},
 * backlog #101) has a fifth, per-IP bucket (default 5/minute): the
 * 5-minute challenge is a Bearer <redacted> for a 6-digit code, so the
 * exchange is an online-guessing surface. It is deliberately separate
 * from the credential bucket (failed exchanges must not starve logins
 * from the same IP, nor vice versa) and, like the refresh and reset
 * buckets, it never resets on success — a completed exchange must not
 * refill a guesser's budget. The per-account half of #101 (repeated
 * failures lock the exchange for that account) lives in
 * {@code AuthService.authenticateTotp}, not here: this filter keys on
 * IPs and cannot know which account a challenge belongs to without
 * doing the service's job.
 *
 * <p>{@code POST /api/messages} has a third, per-<em>user</em> bucket
 * (default 30 sends/minute). Keying on the JWT principal id instead of the
 * IP keeps one spammer from starving everyone behind the same NAT address,
 * and keeps one user from hiding in their household's shared bucket. The
 * filter runs before the security chain, so it parses the
 * {@code Authorization} Bearer <redacted> itself to recover the principal id; a request
 * with a missing/invalid token falls back to a per-IP bucket (the security
 * chain 401s it anyway, but anonymous hammering is still throttled).
 *
 * <p>Listing reports ({@code POST /api/items/{id}/report}, backlog #160)
 * have a seventh, per-<em>user</em> bucket (default 10/minute) on the
 * same keying as messages, for the same reason: the abuse surface is a
 * signed-in account flooding the ADMIN moderation queue, not an IP.
 * The path carries the listing id, so it is matched by shape
 * ({@code /api/items/<digits>/report}) rather than by equality.
 *
 * <p>The account data export ({@code GET /api/users/me/export}, backlog
 * #172) has an eighth, per-<em>user</em> bucket (default 5/minute) on
 * the same keying as messages. It is the one throttled GET in this
 * filter: the export is the heaviest read in the app (every section of
 * the caller's data in one document), so repeated downloads are a
 * usage throttle, never a mistake budget — a completed export does not
 * reset it.
 *
 * <p>The CSP violation report intake ({@code POST
 * /api/security/csp-report}, backlog #204) has a ninth, per-IP bucket
 * (default 60/minute): the endpoint is public by necessity (browsers
 * send reports unauthenticated), so — like the reset/TOTP buckets —
 * it keys on the trusted-proxy-aware client IP and never resets on
 * success. It is sized for browser-generated bursts; the ceiling stops
 * a deliberate flood of the log/metric pipeline, not normal reporting.
 *
 * <p>The frontend error beacon intake ({@code POST
 * /api/telemetry/errors}, backlog #223) has a thirteenth, per-IP bucket
 * (default 60/minute): the endpoint is public by necessity (a crash can
 * happen on an anonymous page), so — like the CSP bucket — it keys on
 * the trusted-proxy-aware client IP and never resets on success. It is
 * sized for crash-loop bursts; the ceiling stops a deliberate flood of
 * the log/metric pipeline, not normal reporting.
 *
 * <p>The step-up re-proof endpoint ({@code POST /api/auth/step-up},
 * backlog #218) has a tenth, per-user bucket (default 5/minute): the
 * endpoint verifies the current password, so it is a password-guessing
 * surface for anyone holding a stolen Bearer token — the bucket keys
 * on the JWT principal (per-IP fallback for a missing/invalid token,
 * like the message bucket) and never resets on success.
 *
 * <p>The password-change endpoint ({@code POST /api/auth/password},
 * backlog #290) has its own per-user bucket (default 5/minute): the
 * endpoint verifies the current password, so it is the same guessing
 * surface for a stolen Bearer token — the bucket keys on the JWT
 * principal (per-IP fallback for a missing/invalid token, like the
 * message bucket) and never resets on success, so a completed change
 * must not refill a guesser's budget. It is deliberately separate
 * from the step-up bucket (which covers only the re-proof) and the
 * credential bucket, so guessing at one surface can never starve
 * the others; a throttled change attempt changes nothing server-side,
 * so the caller's refresh family stays intact.
 *
 * <p>Listing creation ({@code POST /api/items}, backlog #219) has an
 * eleventh, per-<em>user</em> bucket (default 10/minute) on the same
 * keying as the message bucket (JWT principal id, per-IP fallback for
 * a missing/invalid token), never reset on success: the abuse surface
 * is an account spamming listing rows, not a brute-force surface, so
 * the ceiling is sized for human listing (10/min is generous) while
 * staying useless for a script. Sibling item POSTs ({@code /photo},
 * {@code /photos}, {@code /renew}, {@code /publish}) are deliberately
 * NOT in this bucket — they have their own abuse shape and would
 * starve listing creation if they shared it; the collection POST is
 * matched exactly, so they land in their own #228 mutation bucket
 * instead.
 *
 * <p>Order creation ({@code POST /api/orders}, backlog #219) has a
 * twelfth, per-<em>user</em> bucket (default 20/minute) on the same
 * keying, never reset on success: an account hammering order rows
 * also triggers the idempotency, notification and metrics work every
 * order carries, so the create-order path gets its own ceiling,
 * sized higher than the listing bucket because the buy flow is one
 * order per click and a fast buyer is legitimate. Only the
 * collection POST is throttled — the transition POSTs have their own
 * bucket (backlog #228, below), so creating orders can never starve
 * paying for them, nor vice versa.
 *
 * <p>Order transitions ({@code POST /api/orders/{id}/pay}, {@code /cancel},
 * {@code /complete}, {@code /refund}, {@code /ship}, backlog #228) share a
 * per-<em>user</em> bucket (default 20/minute) on the same keying as the
 * message bucket (JWT principal id, per-IP fallback for a missing/invalid
 * token), never reset on success: the five transitions are the money path
 * of a single order, so one account hammering them — paid or not — is one
 * abuse shape, and the bucket must not be refillable by an idempotent
 * re-pay. The path carries the order id, so the transitions are matched
 * by shape ({@code /api/orders/<digits>/(pay|cancel|complete|refund|ship)})
 * rather than by equality. The sibling transition POSTs
 * ({@code /confirm-receipt}, {@code /handover-code/regenerate}) are not in
 * this bucket — they were out of #228's scope and get their own #289
 * bucket, below.
 *
 * <p>Order-tail transitions ({@code POST /api/orders/{id}/confirm-receipt}
 * and {@code POST /api/orders/{id}/handover-code/regenerate}, backlog
 * #289) share a per-<em>user</em> bucket (default 20/minute) on the same
 * keying as the message bucket (JWT principal id, per-IP fallback for a
 * missing/invalid token), never reset on success: receipt confirmation
 * moves an order toward COMPLETED and a regenerated handover code
 * invalidates the previous one, so one account hammering either shape is
 * one abuse shape — and the bucket is deliberately separate from the
 * #228 transition bucket, so transition traffic can never starve
 * receipt/regenerate traffic, nor vice versa. The paths carry the order
 * id, so both shapes are matched by shape rather than by equality.
 *
 * <p>Item mutations ({@code POST /api/items/{id}/photo}, {@code /photos},
 * {@code /renew}, {@code /publish} and {@code PATCH /api/items/{id}/status},
 * backlog #228) share a per-<em>user</em> bucket (default 30/minute) on the
 * same keying, never reset on success: one account editing its own listings
 * is one abuse shape, and the bucket must not be refillable by a
 * successful upload. The ceiling is generous on purpose — a seller adding
 * a gallery one file at a time is legitimate traffic. The collection POST
 * ({@code POST /api/items}) keeps its own #219 bucket, and the
 * {@code /report} POST keeps its #160 bucket; only the mutation shapes
 * land here.
 *
 * <p>Social / money-adjacent creation writes ({@code POST
 * /api/items/{id}/offers}, {@code POST /api/orders/{id}/review},
 * {@code POST /api/orders/{id}/dispute}, {@code POST
 * /api/disputes/{id}/evidence} and {@code POST
 * /api/seller/reports/{id}/appeal}, backlog #282) share a
 * per-<em>user</em> bucket (default 20/minute) on the same keying as
 * the message bucket (JWT principal id, per-IP fallback for a
 * missing/invalid token), never reset on success: one account
 * flooding the negotiation, review, dispute and appeal surfaces is
 * one abuse shape, and these writes must neither consume nor be
 * starved by the neighbouring write buckets. Deliberately NOT in
 * this bucket: the offer lifecycle transitions ({@code
 * /api/offers/{id}/accept}, {@code /decline}, {@code /counter} and
 * friends) have their own #293 bucket, below; the ADMIN
 * dispute/appeal resolutions have their own #297 bucket, below;
 * the seller review-response PUT is a different abuse shape and
 * has its own #298 bucket, below — it never shared #282's bucket,
 * and neither the review edit nor the response may consume or be
 * starved by review creation.
 * (The order-tail POSTs {@code /confirm-receipt} and
 * {@code /handover-code/regenerate} have their own #289 bucket, above
 * — they are not #282's to cover.)
 *
 * <p>Offer lifecycle transitions ({@code POST /api/offers/{id}/accept},
 * {@code /decline}, {@code /counter}, {@code /counter-accept},
 * {@code /counter-decline} and {@code /withdraw}, backlog #293) share a
 * per-<em>user</em> bucket (default 20/minute) on the same keying as
 * the message bucket (JWT principal id, per-IP fallback for a
 * missing/invalid token), never reset on success: accept and
 * counter-accept move a negotiation toward a paid order, so one
 * account hammering any of the six shapes is one abuse shape, and
 * lifecycle traffic must neither consume nor be starved by the #282
 * creation bucket (filing an offer stays there) or the #228
 * order-transition bucket. The path carries the offer id, so the
 * transitions are matched by shape rather than by equality.
 *
 * <p>Passkey challenge issuance and enrolment verification
 * ({@code POST /api/auth/2fa/passkeys/registration/options},
 * {@code POST /api/auth/2fa/passkeys/registration/verify} and
 * {@code POST /api/auth/2fa/passkeys/assertion/options}, backlog
 * #295) share a per-<em>user</em> bucket (default 5/minute) on the
 * same keying as the message bucket (JWT principal id, per-IP
 * fallback for a missing/invalid token), never reset on success:
 * minting challenges is a per-account abuse surface — an account
 * hammering ceremony options can mint challenges at volume — and
 * the bucket must not be refillable by a completed enrolment. The
 * keying split is inherent to the shapes: the two registration
 * shapes are authenticated, so they land on the JWT-principal key,
 * while the assertion-options shape is public by design (its caller
 * is not authenticated yet), so it lands on the per-IP fallback key
 * — assertion traffic can therefore never starve an authenticated
 * user's registration bucket, nor vice versa. Deliberately
 * separate from the TOTP authenticate bucket (backlog #101), which
 * keeps the {@code /2fa/authenticate} exchange itself; the 2FA
 * setup/enable/disable endpoints stay out of this bucket, out of
 * #295's scope.
 *
 * <p>Session and trusted-device revocation ({@code DELETE
 * /api/auth/sessions/{id}}, {@code POST
 * /api/auth/sessions/revoke-others} and {@code DELETE
 * /api/auth/trusted-devices/{id}}, backlog #296) share a per-<em>user</em>
 * bucket (default 5/minute) on the same keying as the message bucket
 * (JWT principal id, per-IP fallback for a missing/invalid token),
 * never reset on success: one account killing sessions or device trust
 * at volume is one abuse shape, and a throttled attempt never reaches
 * the controller, so it revokes nothing and the caller's own sessions
 * stay intact. Deliberately separate from the step-up bucket:
 * revoke-others is step-up gated, but the gate proves the presented
 * proof is fresh, not that the rate is sane — revocation traffic must
 * neither consume nor be starved by re-proof traffic, nor by the
 * credential bucket. The two DELETE shapes are matched before the
 * filter's POST-only gate (like the PATCH item-status sibling), and
 * the revoke-others POST is matched exactly, so the session-list and
 * trusted-device-list GETs stay unthrottled reads.
 *
 * <p>ADMIN dispute/appeal resolutions ({@code POST
 * /api/admin/disputes/{id}/resolve-refund}, {@code POST
 * /api/admin/disputes/{id}/resolve-release}, {@code POST
 * /api/admin/appeals/{id}/uphold} and {@code POST
 * /api/admin/appeals/{id}/reject}, backlog #297) share a per-<em>user</em>
 * bucket (default 20/minute) on the same keying as the message bucket
 * (JWT principal id, per-IP fallback for a missing/invalid token),
 * never reset on success: one ADMIN clearing the dispute and appeal
 * queues is one abuse shape, and a throttled attempt never reaches
 * the controller, so it resolves nothing and the queued dispute or
 * appeal stays exactly as it was. Deliberately separate from the
 * #282 creation bucket (filing a dispute or an appeal stays there)
 * and from every other bucket, so resolution traffic can neither
 * consume nor be starved by them. The ceiling is generous on
 * purpose — a single ADMIN clearing a queue is legitimate traffic,
 * one resolution per click. The four shapes are matched by shape
 * because each path carries a numeric id; the ADMIN list GETs are
 * reads the filter never throttles, and a non-ADMIN caller's single
 * resolution attempt still reaches the security chain and answers
 * 403 (its own principal key keeps it from ever starving an ADMIN's
 * bucket).
 *
 * <p>Review edits and seller review responses ({@code PUT
 * /api/reviews/{id}} and {@code PUT /api/reviews/{id}/response},
 * backlog #298) share a per-<em>user</em> bucket (default 20/minute)
 * on the same keying as the message bucket (JWT principal id,
 * per-IP fallback for a missing/invalid token), never reset on
 * success: one account rewriting its own review or answering reviews
 * at volume is one abuse shape, and a throttled attempt never reaches
 * the controller, so it edits nothing and answers nothing. Deliberately
 * separate from the #282 creation bucket (filing a review stays
 * there), so edit/response traffic can neither consume nor be starved
 * by creation traffic, nor vice versa. The two PUT shapes are matched
 * before the filter's POST-only gate (like the PATCH item-status and
 * DELETE session-revocation siblings), because the path carries the
 * review id and the shapes are matched by shape rather than by
 * equality.
 *
 * <p>Behavior:
 * <ul>
 *   <li>Every attempt on a throttled endpoint consumes one token from its
 *       surface's bucket — login and register share one bucket per IP;
 *       refresh has a separate one; message sends have a per-user one.</li>
 *   <li>An empty bucket short-circuits with {@code 429} in the project's
 *       {@code {code,message,data}} envelope plus a {@code Retry-After}
 *       header (seconds until the next token) and the draft-ietf
 *       {@code RateLimit-Limit} / {@code RateLimit-Remaining} /
 *       {@code RateLimit-Reset} headers for the refusing bucket (backlog
 *       #236). The security chain is never reached.</li>
 *   <li>A <em>successful</em> login (2xx) resets the caller's credential
 *       bucket, so legitimate users are not punished for earlier typos.
 *       Register does not reset — the natural next step is a login, which
 *       does. Refresh never resets its bucket: a successful refresh is the
 *       normal flow, and resetting on success would let an attacker who
 *       guesses one valid token hammer indefinitely. Message sends never
 *       reset either — the bucket is a usage throttle, not a mistake
 *       budget; a successful send must not refill the spam allowance.</li>
 * </ul>
 *
 * <p>Runs just after {@link com.toni.marketplace.common.RequestIdFilter}
 * ({@code HIGHEST_PRECEDENCE + 1}) so a throttled {@code 429} still carries
 * the {@code X-Request-ID} echo, and still before any security processing.
 * Only POSTs to the throttled paths are inspected; everything else passes
 * through untouched.
 *
 * <p>Client identity (backlog #104): by default it is exactly
 * {@code request.getRemoteAddr()} and {@code X-Forwarded-For} is ignored.
 * When the immediate peer is listed in
 * {@code app.security.trusted-proxies} (see
 * {@link TrustedProxiesProperties}), the header is honored by walking the
 * forwarded chain from the nearest hop outwards and taking the first
 * <em>untrusted</em> hop — so a client cannot pick its own bucket key by
 * prepending a spoofed address (the proxy-appended real address is the
 * first untrusted hop; anything the client prepended sits beyond it and
 * is never reached). With an empty trusted list (the default) the old
 * behaviour is unchanged: behind the docker-compose nginx proxy every
 * user would share the proxy's bucket unless the operator lists the
 * proxy's address — trusting the header blindly would let attackers
 * spoof their bucket key, so trust is opt-in and exact-match only.
 * The resolved identity feeds every per-IP bucket in this filter
 * (credential, refresh, password-reset and TOTP) and the message
 * bucket's per-IP fallback; the message bucket's per-user key is
 * unaffected.
 *
 * <p>Honest trade-off, documented here so it stays deliberate: the
 * access token on a message send is parsed twice (once here to recover
 * the principal id, once by the JWT authentication filter). This filter
 * enforces nothing — it only throttles; all auth decisions stay
 * downstream.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1) // RequestIdFilter (HIGHEST_PRECEDENCE) attaches first
public class AuthRateLimitFilter extends OncePerRequestFilter {

  static final String RETRY_AFTER_HEADER = "Retry-After";
  private static final String LOGIN_PATH = "/api/auth/login";
  private static final String REGISTER_PATH = "/api/auth/register";
  private static final String REFRESH_PATH = "/api/auth/refresh";
  private static final String RESET_PATH = "/api/auth/password-reset";
  private static final String RESET_CONFIRM_PATH = "/api/auth/password-reset/confirm";
  private static final String TOTP_AUTH_PATH = "/api/auth/2fa/authenticate";
  private static final String VERIFY_RESEND_PATH = "/api/auth/email-verification/resend";
  private static final String VERIFY_CONFIRM_PATH = "/api/auth/email-verification/confirm";
  private static final String EMAIL_CHANGE_REQUEST_PATH = "/api/auth/email-change";
  private static final String EMAIL_CHANGE_CONFIRM_PATH = "/api/auth/email-change/confirm";
  private static final String MESSAGE_PATH = "/api/messages";
  private static final String MESSAGE_PHOTO_PATH = "/api/messages/photo";

  /**
   * True for the message-send paths (backlog #245): the JSON send and
   * its multipart photo twin share the #76 per-user message bucket, so
   * a photo send can never bypass the throttle a text send hits. The
   * single-message edit/delete paths ({@code /api/messages/{id}}) are
   * matched separately by {@link #isMessageItemPath}.
   */
  private static boolean isMessageSendPath(String path) {
    return MESSAGE_PATH.equals(path) || MESSAGE_PHOTO_PATH.equals(path);
  }
  private static final String EXPORT_PATH = "/api/users/me/export";
  private static final String CSP_REPORT_PATH = "/api/security/csp-report";
  private static final String TELEMETRY_ERROR_PATH = "/api/telemetry/errors";
  private static final String STEP_UP_PATH = "/api/auth/step-up";
  private static final String PASSWORD_CHANGE_PATH = "/api/auth/password";
  private static final String PASSKEY_REGISTRATION_OPTIONS_PATH =
      "/api/auth/2fa/passkeys/registration/options";
  private static final String PASSKEY_REGISTRATION_VERIFY_PATH =
      "/api/auth/2fa/passkeys/registration/verify";
  private static final String PASSKEY_ASSERTION_OPTIONS_PATH =
      "/api/auth/2fa/passkeys/assertion/options";
  private static final String SESSIONS_REVOKE_OTHERS_PATH = "/api/auth/sessions/revoke-others";
  private static final String ITEMS_PATH = "/api/items";
  private static final String ORDERS_PATH = "/api/orders";
  private static final String BEARER_PREFIX = "Bearer ";

  private final AuthRateLimiter credentialLimiter;
  private final AuthRateLimiter refreshLimiter;
  private final AuthRateLimiter messageLimiter;
  private final AuthRateLimiter passwordResetLimiter;
  private final AuthRateLimiter totpLimiter;
  private final AuthRateLimiter emailVerificationLimiter;
  private final AuthRateLimiter emailChangeRequestLimiter;
  private final AuthRateLimiter reportLimiter;
  private final AuthRateLimiter dataExportLimiter;
  private final AuthRateLimiter cspReportLimiter;
  private final AuthRateLimiter telemetryErrorLimiter;
  private final AuthRateLimiter stepUpLimiter;
  private final AuthRateLimiter passwordChangeLimiter;
  private final AuthRateLimiter passkeyLimiter;
  private final AuthRateLimiter listingCreateLimiter;
  private final AuthRateLimiter orderCreateLimiter;
  private final AuthRateLimiter orderTransitionLimiter;
  private final AuthRateLimiter orderReceiptLimiter;
  private final AuthRateLimiter itemMutationLimiter;
  private final AuthRateLimiter socialWriteLimiter;
  private final AuthRateLimiter offerLifecycleLimiter;
  private final AuthRateLimiter sessionRevocationLimiter;
  private final AuthRateLimiter adminResolutionLimiter;
  private final AuthRateLimiter reviewEditLimiter;
  private final JwtTokenService jwt;
  private final ObjectMapper mapper;
  private final Set<String> trustedProxies;

  public AuthRateLimitFilter(@Qualifier("credentialRateLimiter") AuthRateLimiter credentialLimiter,
                             @Qualifier("refreshRateLimiter") AuthRateLimiter refreshLimiter,
                             @Qualifier("messageRateLimiter") AuthRateLimiter messageLimiter,
                             @Qualifier("passwordResetRateLimiter")
                                 AuthRateLimiter passwordResetLimiter,
                             @Qualifier("totpRateLimiter") AuthRateLimiter totpLimiter,
                             @Qualifier("emailVerificationRateLimiter")
                                 AuthRateLimiter emailVerificationLimiter,
                             @Qualifier("emailChangeRequestRateLimiter")
                                 AuthRateLimiter emailChangeRequestLimiter,
                             @Qualifier("reportRateLimiter") AuthRateLimiter reportLimiter,
                             @Qualifier("dataExportRateLimiter")
                                 AuthRateLimiter dataExportLimiter,
                             @Qualifier("cspReportRateLimiter")
                                 AuthRateLimiter cspReportLimiter,
                             @Qualifier("telemetryErrorRateLimiter")
                                 AuthRateLimiter telemetryErrorLimiter,
                             @Qualifier("stepUpRateLimiter")
                                 AuthRateLimiter stepUpLimiter,
                             @Qualifier("passwordChangeRateLimiter")
                                 AuthRateLimiter passwordChangeLimiter,
                             @Qualifier("passkeyRateLimiter")
                                 AuthRateLimiter passkeyLimiter,
                             @Qualifier("listingCreateRateLimiter")
                                 AuthRateLimiter listingCreateLimiter,
                             @Qualifier("orderCreateRateLimiter")
                                 AuthRateLimiter orderCreateLimiter,
                             @Qualifier("orderTransitionRateLimiter")
                                 AuthRateLimiter orderTransitionLimiter,
                             @Qualifier("orderReceiptRateLimiter")
                                 AuthRateLimiter orderReceiptLimiter,
                             @Qualifier("itemMutationRateLimiter")
                                 AuthRateLimiter itemMutationLimiter,
                             @Qualifier("socialWriteRateLimiter")
                                 AuthRateLimiter socialWriteLimiter,
                             @Qualifier("offerLifecycleRateLimiter")
                                 AuthRateLimiter offerLifecycleLimiter,
                             @Qualifier("sessionRevocationRateLimiter")
                                 AuthRateLimiter sessionRevocationLimiter,
                             @Qualifier("adminResolutionRateLimiter")
                                 AuthRateLimiter adminResolutionLimiter,
                             @Qualifier("reviewEditRateLimiter")
                                 AuthRateLimiter reviewEditLimiter,
                             JwtTokenService jwt,
                             ObjectMapper mapper,
                             TrustedProxiesProperties trustedProxies) {
    this.credentialLimiter = credentialLimiter;
    this.refreshLimiter = refreshLimiter;
    this.messageLimiter = messageLimiter;
    this.passwordResetLimiter = passwordResetLimiter;
    this.totpLimiter = totpLimiter;
    this.emailVerificationLimiter = emailVerificationLimiter;
    this.emailChangeRequestLimiter = emailChangeRequestLimiter;
    this.reportLimiter = reportLimiter;
    this.dataExportLimiter = dataExportLimiter;
    this.cspReportLimiter = cspReportLimiter;
    this.telemetryErrorLimiter = telemetryErrorLimiter;
    this.stepUpLimiter = stepUpLimiter;
    this.passwordChangeLimiter = passwordChangeLimiter;
    this.passkeyLimiter = passkeyLimiter;
    this.listingCreateLimiter = listingCreateLimiter;
    this.orderCreateLimiter = orderCreateLimiter;
    this.orderTransitionLimiter = orderTransitionLimiter;
    this.orderReceiptLimiter = orderReceiptLimiter;
    this.itemMutationLimiter = itemMutationLimiter;
    this.socialWriteLimiter = socialWriteLimiter;
    this.offerLifecycleLimiter = offerLifecycleLimiter;
    this.sessionRevocationLimiter = sessionRevocationLimiter;
    this.adminResolutionLimiter = adminResolutionLimiter;
    this.reviewEditLimiter = reviewEditLimiter;
    this.jwt = jwt;
    this.mapper = mapper;
    this.trustedProxies = trustedProxies == null ? Set.of() : trustedProxies.trustedProxySet();
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = pathOf(request);
    if ("GET".equalsIgnoreCase(request.getMethod()) && EXPORT_PATH.equals(path)) {
      // The one throttled GET (backlog #172): see the class javadoc.
      return !dataExportLimiter.isEnabled();
    }
    if (("PUT".equalsIgnoreCase(request.getMethod())
            || "DELETE".equalsIgnoreCase(request.getMethod()))
        && isMessageItemPath(path)) {
      // Message edit/delete (backlog #206): the #76 message bucket
      // covers every message write, not just the POST send.
      return !messageLimiter.isEnabled();
    }
    if ("PATCH".equalsIgnoreCase(request.getMethod()) && isItemStatusPath(path)) {
      // Listing status flip (backlog #228): the one PATCH in the item
      // mutation bucket — it must be checked before the POST-only gate
      // below, or it would pass through unthrottled.
      return !itemMutationLimiter.isEnabled();
    }
    if ("DELETE".equalsIgnoreCase(request.getMethod()) && isSessionRevocationDeletePath(path)) {
      // Session / trusted-device revocation (backlog #296): the two
      // DELETE shapes in the revocation bucket — they must be checked
      // before the POST-only gate below, or they would pass through
      // unthrottled. The session-list and trusted-device-list GETs are
      // reads and stay unthrottled.
      return !sessionRevocationLimiter.isEnabled();
    }
    if ("PUT".equalsIgnoreCase(request.getMethod()) && isReviewEditPath(path)) {
      // Review edits / seller responses (backlog #298): the two PUT
      // shapes in the review-edit bucket — they must be checked
      // before the POST-only gate below, or they would pass through
      // unthrottled. Review creation (POST /api/orders/{id}/review)
      // stays in the #282 bucket and never reaches this branch.
      return !reviewEditLimiter.isEnabled();
    }
    if (!"POST".equalsIgnoreCase(request.getMethod())) {
      return true;
    }
    if (isMessageSendPath(path)) {
      return !messageLimiter.isEnabled();
    }
    if (CSP_REPORT_PATH.equals(path)) {
      return !cspReportLimiter.isEnabled();
    }
    if (TELEMETRY_ERROR_PATH.equals(path)) {
      return !telemetryErrorLimiter.isEnabled();
    }
    if (STEP_UP_PATH.equals(path)) {
      // Step-up re-proof (backlog #218): own per-user bucket on the
      // message keying (JWT principal, per-IP fallback), never reset
      // on success — a completed proof must not refill a password
      // guesser's budget.
      return !stepUpLimiter.isEnabled();
    }
    if (PASSWORD_CHANGE_PATH.equals(path)) {
      // Password change (backlog #290): own per-user bucket on the
      // message keying (JWT principal, per-IP fallback), never reset
      // on success — a completed change must not refill a password
      // guesser's budget. Kept apart from the step-up bucket (which
      // covers only the re-proof) and the credential bucket, so
      // guessing at one surface can never starve the others.
      return !passwordChangeLimiter.isEnabled();
    }
    if (isPasskeyChallengePath(path)) {
      // Passkey challenge issuance / enrolment verification (backlog
      // #295): own per-user bucket on the message keying (JWT
      // principal, per-IP fallback — the assertion-options shape is
      // public, so it takes the fallback key), never reset on
      // success. All three shapes are exact paths, so no sibling
      // passkey shape (list, delete, rename) lands here.
      return !passkeyLimiter.isEnabled();
    }
    if (SESSIONS_REVOKE_OTHERS_PATH.equals(path)) {
      // Revoke-all-other-sessions (backlog #296): the POST member of
      // the session-revocation bucket — the exact match keeps the
      // session-list GET and the one-at-a-time DELETE (handled above)
      // out of this branch, while sharing the bucket with them.
      return !sessionRevocationLimiter.isEnabled();
    }
    if (ITEMS_PATH.equals(path)) {
      // Listing creation (backlog #219): own per-user bucket on the
      // message keying (JWT principal, per-IP fallback), never reset
      // on success — a created listing must not refill the spam
      // allowance. The exact match keeps the sibling item POSTs
      // (/photo, /photos, /renew, /publish) out of this bucket —
      // they have their own #228 bucket below.
      return !listingCreateLimiter.isEnabled();
    }
    if (ORDERS_PATH.equals(path)) {
      // Order creation (backlog #219): own per-user bucket on the
      // message keying, never reset on success — a placed order must
      // not refill the allowance. The exact match keeps the
      // transition POSTs (/pay, /cancel, ...) out of this bucket —
      // they have their own #228 bucket below.
      return !orderCreateLimiter.isEnabled();
    }
    if (isOrderTransitionPath(path)) {
      // Order transitions (backlog #228): own per-user bucket on the
      // message keying (JWT principal, per-IP fallback), never reset
      // on success — an idempotent re-pay must not refill the
      // allowance. Shape-matched because the path carries the order id.
      return !orderTransitionLimiter.isEnabled();
    }
    if (isOrderReceiptPath(path)) {
      // Order-tail transitions (backlog #289): own per-user bucket on
      // the message keying (JWT principal, per-IP fallback), never
      // reset on success — an idempotent re-confirm must not refill
      // the allowance. Shape-matched because the path carries the
      // order id; kept apart from the #228 bucket so neither traffic
      // shape can starve the other.
      return !orderReceiptLimiter.isEnabled();
    }
    if (isItemMutationPath(path)) {
      // Item mutations (backlog #228): own per-user bucket on the
      // message keying, never reset on success — a successful upload
      // must not refill the allowance. Shape-matched because the path
      // carries the listing id; the PATCH /status sibling is handled
      // above (it is not a POST).
      return !itemMutationLimiter.isEnabled();
    }
    if (isSocialWritePath(path)) {
      // Social / money-adjacent creation writes (backlog #282):
      // own per-user bucket on the message keying, never reset on
      // success — a filed offer, review, dispute, evidence photo or
      // appeal must not refill the allowance. Shape-matched because
      // every path carries an id segment.
      return !socialWriteLimiter.isEnabled();
    }
    if (isOfferLifecyclePath(path)) {
      // Offer lifecycle transitions (backlog #293): own per-user
      // bucket on the message keying, never reset on success — an
      // accepted, declined, countered or withdrawn offer must not
      // refill the allowance. Shape-matched because the path carries
      // the offer id; filing an offer stays in the #282 bucket above.
      return !offerLifecycleLimiter.isEnabled();
    }
    if (isAdminResolutionPath(path)) {
      // ADMIN dispute/appeal resolutions (backlog #297): own
      // per-user bucket on the message keying, never reset on
      // success — a completed resolution must not refill the
      // allowance. Shape-matched because each path carries a
      // numeric id; the ADMIN list GETs never reach this branch
      // (they are not POSTs), and filing a dispute or an appeal
      // stays in the #282 bucket above.
      return !adminResolutionLimiter.isEnabled();
    }
    if (isReportPath(path)) {
      return !reportLimiter.isEnabled();
    }
    if (RESET_PATH.equals(path) || RESET_CONFIRM_PATH.equals(path)) {
      return !passwordResetLimiter.isEnabled();
    }
    if (TOTP_AUTH_PATH.equals(path)) {
      return !totpLimiter.isEnabled();
    }
    if (EMAIL_CHANGE_REQUEST_PATH.equals(path)) {
      // Email-change request (backlog #294): own per-IP bucket on the
      // verification-bucket pattern (5/min, never reset on success) —
      // a staged change must not refill the mail-bombing allowance.
      // The exact match keeps the confirm sibling out of this bucket:
      // it stays in the verification bucket below.
      return !emailChangeRequestLimiter.isEnabled();
    }
    if (VERIFY_RESEND_PATH.equals(path) || VERIFY_CONFIRM_PATH.equals(path)
        || EMAIL_CHANGE_CONFIRM_PATH.equals(path)) {
      return !emailVerificationLimiter.isEnabled();
    }
    if (LOGIN_PATH.equals(path) || REGISTER_PATH.equals(path) || REFRESH_PATH.equals(path)) {
      return !credentialLimiter.isEnabled();
    }
    return true;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
    String path = pathOf(request);
    if (EXPORT_PATH.equals(path)) {
      // Account data export (backlog #172): own per-user bucket on the
      // message keying (JWT principal, per-IP fallback), never reset on
      // success — a completed download must not refill the allowance
      // for the heaviest read in the app.
      String key = messageKey(request);
      if (!dataExportLimiter.tryConsume(key)) {
        writeTooManyRequests(response, dataExportLimiter, key);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (isMessageSendPath(path) || isMessageItemPath(path)) {
      String key = messageKey(request);
      if (!messageLimiter.tryConsume(key)) {
        writeTooManyRequests(response, messageLimiter, key);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (isReportPath(path)) {
      // Listing reports (backlog #160): own per-user bucket on the
      // message keying (JWT principal, per-IP fallback), never reset on
      // success — a filed report must not refill the queue-flooding
      // allowance.
      String key = messageKey(request);
      if (!reportLimiter.tryConsume(key)) {
        writeTooManyRequests(response, reportLimiter, key);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (CSP_REPORT_PATH.equals(path)) {
      // CSP violation reports (backlog #204): own per-IP bucket, never
      // reset on success — an accepted report must not refill the
      // allowance. Per-IP (not per-user) because the endpoint is public
      // by necessity: browsers send reports without a session, so the
      // trusted-proxy-aware client IP is the only identity available.
      String ip = clientIp(request);
      if (!cspReportLimiter.tryConsume(ip)) {
        writeTooManyRequests(response, cspReportLimiter, ip);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (TELEMETRY_ERROR_PATH.equals(path)) {
      // Frontend error beacons (backlog #223): own per-IP bucket, never
      // reset on success — an accepted beacon must not refill the
      // allowance. Per-IP (not per-user) because the endpoint is public
      // by necessity: a crash can happen on an anonymous page, so the
      // trusted-proxy-aware client IP is the only identity available.
      // Sized like the CSP bucket (60/min): a crash loop in one tab
      // can otherwise flood the log/metric pipeline.
      String ip = clientIp(request);
      if (!telemetryErrorLimiter.tryConsume(ip)) {
        writeTooManyRequests(response, telemetryErrorLimiter, ip);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (STEP_UP_PATH.equals(path)) {
      // Step-up re-proof (backlog #218): own per-user bucket on the
      // message keying, never reset on success — a password guesser
      // holding a stolen Bearer token gets 5 attempts/minute, not
      // unlimited tries.
      String key = messageKey(request);
      if (!stepUpLimiter.tryConsume(key)) {
        writeTooManyRequests(response, stepUpLimiter, key);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (PASSWORD_CHANGE_PATH.equals(path)) {
      // Password change (backlog #290): own per-user bucket on the
      // message keying (JWT principal id, per-IP fallback for a
      // missing/invalid token — the security chain 401s anonymous
      // changes, but anonymous hammering is still throttled), never
      // reset on success — a completed change must not refill the
      // allowance, and change traffic must not touch the step-up or
      // credential buckets. A throttled attempt never reaches the
      // controller, so it revokes nothing and the caller's refresh
      // family stays intact.
      String key = messageKey(request);
      if (!passwordChangeLimiter.tryConsume(key)) {
        writeTooManyRequests(response, passwordChangeLimiter, key);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (isPasskeyChallengePath(path)) {
      // Passkey challenge issuance / enrolment verification (backlog
      // #295): own per-user bucket on the message keying (JWT
      // principal id, per-IP fallback — the public assertion-options
      // shape takes the fallback key, since its caller holds no
      // Bearer token yet), never reset on success — a completed
      // enrolment must not refill the allowance, and challenge
      // traffic must not touch the TOTP authenticate bucket. A
      // throttled attempt never reaches the controller, so it mints
      // no challenge and consumes none.
      String key = messageKey(request);
      if (!passkeyLimiter.tryConsume(key)) {
        writeTooManyRequests(response, passkeyLimiter, key);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (isSessionRevocationDeletePath(path) || SESSIONS_REVOKE_OTHERS_PATH.equals(path)) {
      // Session / trusted-device revocation (backlog #296): own
      // per-user bucket on the message keying (JWT principal id,
      // per-IP fallback for a missing/invalid token — the security
      // chain 401s anonymous revokes, but anonymous hammering is
      // still throttled), never reset on success — a completed
      // revoke must not refill the allowance, and revocation traffic
      // must not touch the step-up or credential buckets. A throttled
      // attempt never reaches the controller, so it revokes nothing
      // and the caller's own sessions and trusted devices stay intact.
      // The DELETE shapes reach this branch via shouldNotFilter's
      // DELETE check; the revoke-others POST via its POST check.
      String key = messageKey(request);
      if (!sessionRevocationLimiter.tryConsume(key)) {
        writeTooManyRequests(response, sessionRevocationLimiter, key);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (ITEMS_PATH.equals(path)) {
      // Listing creation (backlog #219): own per-user bucket on the
      // message keying (JWT principal id, per-IP fallback for a
      // missing/invalid token — the security chain 401s anonymous
      // creates, but anonymous hammering is still throttled), never
      // reset on success — a created listing must not refill the
      // listing-spam allowance.
      String key = messageKey(request);
      if (!listingCreateLimiter.tryConsume(key)) {
        writeTooManyRequests(response, listingCreateLimiter, key);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (ORDERS_PATH.equals(path)) {
      // Order creation (backlog #219): own per-user bucket on the
      // message keying, never reset on success — a placed order must
      // not refill the allowance. Order rows trigger idempotency,
      // notification and metrics work, so create-order gets its own
      // ceiling apart from the listing bucket.
      String key = messageKey(request);
      if (!orderCreateLimiter.tryConsume(key)) {
        writeTooManyRequests(response, orderCreateLimiter, key);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (isOrderTransitionPath(path)) {
      // Order transitions (backlog #228): own per-user bucket on the
      // message keying (JWT principal id, per-IP fallback for a
      // missing/invalid token — the security chain 401s anonymous
      // transitions, but anonymous hammering is still throttled),
      // never reset on success — an idempotent re-pay must not refill
      // the transition allowance, and transition traffic must not
      // touch the order-create bucket.
      String key = messageKey(request);
      if (!orderTransitionLimiter.tryConsume(key)) {
        writeTooManyRequests(response, orderTransitionLimiter, key);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (isOrderReceiptPath(path)) {
      // Order-tail transitions (backlog #289): own per-user bucket on
      // the message keying (JWT principal id, per-IP fallback for a
      // missing/invalid token — the security chain 401s anonymous
      // tails, but anonymous hammering is still throttled), never
      // reset on success — an idempotent re-confirm must not refill
      // the allowance, and receipt/regenerate traffic must not touch
      // the #228 transition bucket.
      String key = messageKey(request);
      if (!orderReceiptLimiter.tryConsume(key)) {
        writeTooManyRequests(response, orderReceiptLimiter, key);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (isItemMutationPath(path) || isItemStatusPath(path)) {
      // Item mutations (backlog #228): own per-user bucket on the
      // message keying, never reset on success — a successful upload
      // must not refill the allowance, and mutation traffic must not
      // touch the listing-create bucket. The PATCH /status sibling
      // reaches this branch via shouldNotFilter's PATCH check above;
      // the POST shapes (photo/photos/renew/publish) via the matcher.
      String key = messageKey(request);
      if (!itemMutationLimiter.tryConsume(key)) {
        writeTooManyRequests(response, itemMutationLimiter, key);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (isSocialWritePath(path)) {
      // Social / money-adjacent creation writes (backlog #282):
      // own per-user bucket on the message keying (JWT principal id,
      // per-IP fallback for a missing/invalid token — the security
      // chain 401s anonymous writes, but anonymous hammering is still
      // throttled), never reset on success — a filed offer, review,
      // dispute, evidence photo or appeal must not refill the
      // allowance, and this traffic must not touch the neighbouring
      // write buckets.
      String key = messageKey(request);
      if (!socialWriteLimiter.tryConsume(key)) {
        writeTooManyRequests(response, socialWriteLimiter, key);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (isOfferLifecyclePath(path)) {
      // Offer lifecycle transitions (backlog #293): own per-user
      // bucket on the message keying (JWT principal id, per-IP
      // fallback for a missing/invalid token — the security chain
      // 401s anonymous transitions, but anonymous hammering is still
      // throttled), never reset on success — an accepted, declined,
      // countered or withdrawn offer must not refill the allowance,
      // and lifecycle traffic must not touch the #282 creation bucket
      // or the #228 order-transition bucket.
      String key = messageKey(request);
      if (!offerLifecycleLimiter.tryConsume(key)) {
        writeTooManyRequests(response, offerLifecycleLimiter, key);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (isAdminResolutionPath(path)) {
      // ADMIN dispute/appeal resolutions (backlog #297): own
      // per-user bucket on the message keying (JWT principal id,
      // per-IP fallback for a missing/invalid token — the security
      // chain 403s non-ADMIN callers, but anonymous hammering is
      // still throttled), never reset on success — a completed
      // resolution must not refill the allowance, and resolution
      // traffic must not touch the #282 creation bucket or any
      // other bucket. A throttled attempt never reaches the
      // controller, so it resolves nothing and the queued dispute
      // or appeal stays exactly as it was.
      String key = messageKey(request);
      if (!adminResolutionLimiter.tryConsume(key)) {
        writeTooManyRequests(response, adminResolutionLimiter, key);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (isReviewEditPath(path)) {
      // Review edits / seller responses (backlog #298): own per-user
      // bucket on the message keying (JWT principal id, per-IP
      // fallback for a missing/invalid token — the security chain
      // 401s anonymous edits, but anonymous hammering is still
      // throttled), never reset on success — a completed edit or
      // response must not refill the allowance, and edit/response
      // traffic must not touch the #282 creation bucket. A throttled
      // attempt never reaches the controller, so it edits nothing
      // and answers nothing. The PUT shapes reach this branch via
      // shouldNotFilter's PUT check above.
      String key = messageKey(request);
      if (!reviewEditLimiter.tryConsume(key)) {
        writeTooManyRequests(response, reviewEditLimiter, key);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (RESET_PATH.equals(path) || RESET_CONFIRM_PATH.equals(path)) {
      // Password reset (backlog #90): both endpoints share one per-IP
      // bucket — the request endpoint is a mail-bombing surface, the
      // confirm endpoint a token-guessing surface. Never reset on
      // success: a completed reset must not refill a guesser's budget.
      String ip = clientIp(request);
      if (!passwordResetLimiter.tryConsume(ip)) {
        writeTooManyRequests(response, passwordResetLimiter, ip);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (TOTP_AUTH_PATH.equals(path)) {
      // Second-factor exchange (backlog #101): own per-IP bucket, never
      // reset on success (same rationale as the reset bucket above).
      String ip = clientIp(request);
      if (!totpLimiter.tryConsume(ip)) {
        writeTooManyRequests(response, totpLimiter, ip);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (EMAIL_CHANGE_REQUEST_PATH.equals(path)) {
      // Email-change request (backlog #294): own per-IP bucket, never
      // reset on success — a staged change must not refill the
      // mail-bombing allowance, and request traffic must not touch the
      // verification/confirm bucket below. A throttled attempt never
      // reaches the controller, so it stages nothing and mails nothing.
      String ip = clientIp(request);
      if (!emailChangeRequestLimiter.tryConsume(ip)) {
        writeTooManyRequests(response, emailChangeRequestLimiter, ip);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (VERIFY_RESEND_PATH.equals(path) || VERIFY_CONFIRM_PATH.equals(path)
        || EMAIL_CHANGE_CONFIRM_PATH.equals(path)) {
      // Email verification (backlog #138): both endpoints share one per-IP
      // bucket — the resend endpoint is a mail-bombing surface, the
      // confirm endpoint a token-guessing surface. The email-change
      // confirm (backlog #208) joins the same bucket: it is the same
      // kind of token-guessing surface. Never reset on
      // success: a completed verification must not refill a guesser's
      // budget (the backlog #101 pattern).
      String ip = clientIp(request);
      if (!emailVerificationLimiter.tryConsume(ip)) {
        writeTooManyRequests(response, emailVerificationLimiter, ip);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    AuthRateLimiter limiter = REFRESH_PATH.equals(path) ? refreshLimiter : credentialLimiter;
    String ip = clientIp(request);
    if (!limiter.tryConsume(ip)) {
      writeTooManyRequests(response, limiter, ip);
      return;
    }
    if (LOGIN_PATH.equals(path)) {
      StatusCapture wrapped = new StatusCapture(response);
      chain.doFilter(request, wrapped);
      if (wrapped.getStatus() / 100 == 2) {
        credentialLimiter.reset(ip);
      }
    } else {
      chain.doFilter(request, response);
    }
  }

  /**
   * Bucket key for a message send: the JWT principal id when the request
   * presents a parseable access token, otherwise the client IP. The "u:" /
   * "ip:" prefixes keep the two namespaces from colliding in the same
   * limiter map.
   */
  private String messageKey(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith(BEARER_PREFIX)) {
      try {
        long userId = jwt.parseAccessToken(header.substring(BEARER_PREFIX.length()).trim())
            .userId();
        return "u:" + userId;
      } catch (RuntimeException ignored) {
        // Missing, malformed, expired or wrongly-signed token: throttle by
        // IP. The JWT filter downstream will 401 the request anyway — the
        // bucket here only stops anonymous hammering.
      }
    }
    return "ip:" + clientIp(request);
  }

  /**
   * True for the report-creation path {@code POST /api/items/{id}/report}
   * (backlog #160): the id segment is numeric (listing ids are), which
   * keeps the sibling item POSTs ({@code /photo}, {@code /photos}) and
   * the collection POST ({@code /api/items}) out of this bucket.
   */
  private static boolean isReportPath(String path) {
    return path != null && path.matches("/api/items/\\d+/report");
  }

  /**
   * True for the passkey challenge-issuance / enrolment-verification
   * POSTs (backlog #295): {@code POST
   * /api/auth/2fa/passkeys/registration/options}, {@code POST
   * /api/auth/2fa/passkeys/registration/verify} and {@code POST
   * /api/auth/2fa/passkeys/assertion/options}. All three are exact
   * paths, which keeps the sibling passkey shapes out: the list GET,
   * the delete and the rename are not POSTs to these paths at all,
   * and the {@code /2fa/authenticate} exchange stays in the TOTP
   * bucket (backlog #101), out of #295's scope.
   */
  private static boolean isPasskeyChallengePath(String path) {
    return PASSKEY_REGISTRATION_OPTIONS_PATH.equals(path)
        || PASSKEY_REGISTRATION_VERIFY_PATH.equals(path)
        || PASSKEY_ASSERTION_OPTIONS_PATH.equals(path);
  }

  /**
   * True for the session / trusted-device revocation DELETEs (backlog
   * #296): {@code DELETE /api/auth/sessions/{id}} and {@code DELETE
   * /api/auth/trusted-devices/{id}}. The session id is the family root
   * jti (a UUID-shaped string, not numeric), so the session shape is
   * matched as one non-empty segment; the device id is numeric. The
   * revoke-others POST is not here — it is an exact path matched
   * separately ({@link #SESSIONS_REVOKE_OTHERS_PATH}) — and the
   * session-list / trusted-device-list GETs never reach this matcher
   * (the filter only inspects DELETEs here).
   */
  private static boolean isSessionRevocationDeletePath(String path) {
    return path != null
        && (path.matches("/api/auth/sessions/[^/]+")
            || path.matches("/api/auth/trusted-devices/\\d+"));
  }

  /**
   * True for the social / money-adjacent creation writes (backlog
   * #282): {@code POST /api/items/{id}/offers}, {@code POST
   * /api/orders/{id}/review}, {@code POST /api/orders/{id}/dispute},
   * {@code POST /api/disputes/{id}/evidence} and {@code POST
   * /api/seller/reports/{id}/appeal}. Every id segment is numeric,
   * which keeps the sibling shapes out: the collection POSTs
   * ({@code /api/items}, {@code /api/orders}), the {@code /report}
   * POST, the item mutation POSTs and the order transition POSTs
   * all have their own buckets (or, for the offer lifecycle and
   * ADMIN resolution POSTs, no bucket by #282's scope), and the
   * evidence GET/DELETE siblings are not POSTs at all.
   */
  private static boolean isSocialWritePath(String path) {
    return path != null
        && (path.matches("/api/items/\\d+/offers")
            || path.matches("/api/orders/\\d+/review")
            || path.matches("/api/orders/\\d+/dispute")
            || path.matches("/api/disputes/\\d+/evidence")
            || path.matches("/api/seller/reports/\\d+/appeal"));
  }

  /**
   * True for the offer lifecycle POSTs ({@code POST
   * /api/offers/{id}/accept}, {@code /decline}, {@code /counter},
   * {@code /counter-accept}, {@code /counter-decline} and
   * {@code /withdraw}, backlog #293): the id segment is numeric
   * (offer ids are), which keeps the collection-adjacent shapes out —
   * filing an offer ({@code POST /api/items/{id}/offers}) lives in
   * the #282 bucket and the {@code /api/offers/made} and
   * {@code /api/offers/received} reads are GETs the filter never
   * throttles.
   */
  private static boolean isOfferLifecyclePath(String path) {
    return path != null
        && path.matches("/api/offers/\\d+/(accept|decline|counter|counter-accept|counter-decline|withdraw)");
  }

  /**
   * True for the ADMIN dispute/appeal resolution POSTs
   * ({@code POST /api/admin/disputes/{id}/resolve-refund},
   * {@code POST /api/admin/disputes/{id}/resolve-release},
   * {@code POST /api/admin/appeals/{id}/uphold} and {@code POST
   * /api/admin/appeals/{id}/reject}, backlog #297): the id segment
   * is numeric (dispute and appeal ids are), which keeps the ADMIN
   * list GETs out (they are not POSTs at all) and the seller-side
   * dispute/appeal filing POSTs out — filing stays in the #282
   * bucket, and the admin report dismiss/action POSTs are a
   * different queue with their own pending bucket (#304).
   */
  private static boolean isAdminResolutionPath(String path) {
    return path != null
        && (path.matches("/api/admin/disputes/\\d+/(resolve-refund|resolve-release)")
            || path.matches("/api/admin/appeals/\\d+/(uphold|reject)"));
  }

  /**
   * True for the review edit and seller-response PUTs ({@code PUT
   * /api/reviews/{id}} and {@code PUT /api/reviews/{id}/response},
   * backlog #298): the id segment is numeric (review ids are), which
   * keeps the sibling shapes out — review creation ({@code POST
   * /api/orders/{id}/review}) lives in the #282 bucket, the seller
   * review list reads are GETs the filter never throttles, and no
   * other PUT touches /api/reviews.
   */
  private static boolean isReviewEditPath(String path) {
    return path != null
        && (path.matches("/api/reviews/\\d+")
            || path.matches("/api/reviews/\\d+/response"));
  }

  /**
   * True for the order transition POSTs ({@code POST
   * /api/orders/{id}/pay}, {@code /cancel}, {@code /complete},
   * {@code /refund}, {@code /ship}, backlog #228): the id segment is
   * numeric (order ids are), which keeps the collection POST
   * ({@code /api/orders}) and the sibling order-tail POSTs
   * ({@code /confirm-receipt}, {@code /handover-code/regenerate}) out
   * of this bucket — those two have their own #289 bucket, below.
   */
  private static boolean isOrderTransitionPath(String path) {
    return path != null && path.matches("/api/orders/\\d+/(pay|cancel|complete|refund|ship)");
  }

  /**
   * True for the order-tail POSTs ({@code POST
   * /api/orders/{id}/confirm-receipt} and {@code POST
   * /api/orders/{id}/handover-code/regenerate}, backlog #289): the id
   * segment is numeric (order ids are), which keeps the collection
   * POST ({@code /api/orders}) and the #228 transition POSTs out of
   * this bucket — the two shapes share this one bucket because they
   * are the tail of the same money path.
   */
  private static boolean isOrderReceiptPath(String path) {
    return path != null
        && (path.matches("/api/orders/\\d+/confirm-receipt")
            || path.matches("/api/orders/\\d+/handover-code/regenerate"));
  }

  /**
   * True for the item mutation POSTs ({@code POST
   * /api/items/{id}/photo}, {@code /photos}, {@code /renew},
   * {@code /publish}, backlog #228): the id segment is numeric, which
   * keeps the collection POST ({@code /api/items}), the
   * {@code /report} POST and the {@code /photos/order} PUT out of this
   * bucket — the reorder PUT was out of #228's scope.
   */
  private static boolean isItemMutationPath(String path) {
    return path != null && path.matches("/api/items/\\d+/(photos?|renew|publish)");
  }

  /**
   * True for the listing status PATCH ({@code PATCH
   * /api/items/{id}/status}, backlog #228): the mutation bucket's one
   * non-POST member, matched separately because the filter's POST-only
   * gate would otherwise skip it. The sibling {@code PATCH
   * /api/items/{id}} (edit) is deliberately not here — #228 named only
   * the status flip.
   */
  private static boolean isItemStatusPath(String path) {
    return path != null && path.matches("/api/items/\\d+/status");
  }

  /**
   * True for the single-message paths {@code PUT/DELETE
   * /api/messages/{id}} (backlog #206): the id segment is numeric
   * (message ids are), which keeps the collection path
   * ({@code /api/messages}) and the {@code /unread-count} sibling out
   * of this matcher — the collection POST is matched exactly, and
   * unread-count is a GET the filter never throttles.
   */
  private static boolean isMessageItemPath(String path) {
    return path != null && path.matches("/api/messages/\\d+");
  }

  /**
   * Writes the 429 envelope for a throttled request (backlog #236): the
   * existing {@code Retry-After} (seconds until the next single token)
   * plus the draft-ietf {@code RateLimit-*} headers for the bucket that
   * refused — {@code RateLimit-Limit} (the bucket quota),
   * {@code RateLimit-Remaining} (whole tokens left, 0 on a denial since a
   * denied request consumes nothing) and {@code RateLimit-Reset}
   * (seconds until the bucket is full again). The headers ride only the
   * 429: successful and unthrottled responses carry none of them, so a
   * client can tell "throttled, and by how much" without tripping the
   * limit to learn it — it still has to trip it once, since only the
   * refusal reveals the budget.
   */
  private void writeTooManyRequests(HttpServletResponse response, AuthRateLimiter limiter,
                                    String key) throws IOException {
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.setHeader(RETRY_AFTER_HEADER, Long.toString(limiter.retryAfterSeconds(key)));
    AuthRateLimiter.RateLimitState state = limiter.snapshot(key);
    response.setHeader("RateLimit-Limit", Integer.toString(state.limit()));
    response.setHeader("RateLimit-Remaining", Long.toString(state.remaining()));
    response.setHeader("RateLimit-Reset", Long.toString(state.resetSeconds()));
    mapper.writeValue(response.getWriter(),
        ApiResponse.fail(HttpStatus.TOO_MANY_REQUESTS.value(), "too many requests"));
  }

  private static String pathOf(HttpServletRequest request) {
    String path = request.getServletPath();
    if (path == null || path.isEmpty()) {
      path = request.getRequestURI();
    }
    return path;
  }

  private String clientIp(HttpServletRequest request) {
    return resolveClientIp(request.getRemoteAddr(),
        request.getHeader("X-Forwarded-For"), trustedProxies);
  }

  /**
   * Resolves the bucket identity for a request (backlog #104).
   *
   * <ul>
   *   <li>No/blank remote address → {@code "unknown"} (the limiter's
   *       shared fallback bucket).</li>
   *   <li>Remote address not in {@code trustedProxies} (or the list is
   *       empty) → the remote address itself; the header is ignored
   *       entirely, so an untrusted peer cannot spoof its key.</li>
   *   <li>Remote address trusted but no usable header → the remote
   *       address.</li>
   *   <li>Remote address trusted with a header → walk the parsed chain
   *       from the last (nearest) hop outwards and return the first
   *       hop that is not itself a trusted proxy. A client-supplied
   *       spoofed prefix sits beyond the proxy-appended real address
   *       and is therefore never selected. If every hop is trusted,
   *       the first (leftmost) hop is the original client as forwarded
   *       by trusted proxies only, so it is returned.</li>
   * </ul>
   */
  static String resolveClientIp(String remoteAddr, String xForwardedFor,
                                Set<String> trustedProxies) {
    if (remoteAddr == null || remoteAddr.isBlank()) {
      return "unknown";
    }
    String remote = remoteAddr.trim();
    if (trustedProxies == null || trustedProxies.isEmpty()
        || !trustedProxies.contains(remote)) {
      return remote;
    }
    if (xForwardedFor == null || xForwardedFor.isBlank()) {
      return remote;
    }
    List<String> chain = new ArrayList<>();
    for (String hop : xForwardedFor.split(",")) {
      String trimmed = hop.trim();
      if (!trimmed.isEmpty()) {
        chain.add(trimmed);
      }
    }
    if (chain.isEmpty()) {
      return remote;
    }
    for (int i = chain.size() - 1; i >= 0; i--) {
      if (!trustedProxies.contains(chain.get(i))) {
        return chain.get(i);
      }
    }
    return chain.get(0);
  }

  /**
   * Records the status the downstream chain sets, so the filter can tell a
   * successful login (2xx → reset the bucket) from a failed one. Covers both
   * {@code setStatus} and {@code sendError} paths.
   */
  private static final class StatusCapture extends HttpServletResponseWrapper {
    private int status = HttpServletResponse.SC_OK;

    StatusCapture(HttpServletResponse response) {
      super(response);
    }

    @Override
    public void setStatus(int sc) {
      this.status = sc;
      super.setStatus(sc);
    }

    @Override
    public void sendError(int sc) throws IOException {
      this.status = sc;
      super.sendError(sc);
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
      this.status = sc;
      super.sendError(sc, msg);
    }

    @Override
    public int getStatus() {
      return status;
    }
  }
}
