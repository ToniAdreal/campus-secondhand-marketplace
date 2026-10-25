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
 * matched exactly, so they pass through untouched.
 *
 * <p>Order creation ({@code POST /api/orders}, backlog #219) has a
 * twelfth, per-<em>user</em> bucket (default 20/minute) on the same
 * keying, never reset on success: an account hammering order rows
 * also triggers the idempotency, notification and metrics work every
 * order carries, so the create-order path gets its own ceiling,
 * sized higher than the listing bucket because the buy flow is one
 * order per click and a fast buyer is legitimate. Only the
 * collection POST is throttled — pay/cancel/complete/refund carry
 * their own semantics and are left alone.
 *
 * <p>Behavior:
 * <ul>
 *   <li>Every attempt on a throttled endpoint consumes one token from its
 *       surface's bucket — login and register share one bucket per IP;
 *       refresh has a separate one; message sends have a per-user one.</li>
 *   <li>An empty bucket short-circuits with {@code 429} in the project's
 *       {@code {code,message,data}} envelope plus a {@code Retry-After}
 *       header (seconds until the next token). The security chain is never
 *       reached.</li>
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
  private static final String EMAIL_CHANGE_CONFIRM_PATH = "/api/auth/email-change/confirm";
  private static final String MESSAGE_PATH = "/api/messages";
  private static final String EXPORT_PATH = "/api/users/me/export";
  private static final String CSP_REPORT_PATH = "/api/security/csp-report";
  private static final String TELEMETRY_ERROR_PATH = "/api/telemetry/errors";
  private static final String STEP_UP_PATH = "/api/auth/step-up";
  private static final String ITEMS_PATH = "/api/items";
  private static final String ORDERS_PATH = "/api/orders";
  private static final String BEARER_PREFIX = "Bearer ";

  private final AuthRateLimiter credentialLimiter;
  private final AuthRateLimiter refreshLimiter;
  private final AuthRateLimiter messageLimiter;
  private final AuthRateLimiter passwordResetLimiter;
  private final AuthRateLimiter totpLimiter;
  private final AuthRateLimiter emailVerificationLimiter;
  private final AuthRateLimiter reportLimiter;
  private final AuthRateLimiter dataExportLimiter;
  private final AuthRateLimiter cspReportLimiter;
  private final AuthRateLimiter telemetryErrorLimiter;
  private final AuthRateLimiter stepUpLimiter;
  private final AuthRateLimiter listingCreateLimiter;
  private final AuthRateLimiter orderCreateLimiter;
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
                             @Qualifier("reportRateLimiter") AuthRateLimiter reportLimiter,
                             @Qualifier("dataExportRateLimiter")
                                 AuthRateLimiter dataExportLimiter,
                             @Qualifier("cspReportRateLimiter")
                                 AuthRateLimiter cspReportLimiter,
                             @Qualifier("telemetryErrorRateLimiter")
                                 AuthRateLimiter telemetryErrorLimiter,
                             @Qualifier("stepUpRateLimiter")
                                 AuthRateLimiter stepUpLimiter,
                             @Qualifier("listingCreateRateLimiter")
                                 AuthRateLimiter listingCreateLimiter,
                             @Qualifier("orderCreateRateLimiter")
                                 AuthRateLimiter orderCreateLimiter,
                             JwtTokenService jwt,
                             ObjectMapper mapper,
                             TrustedProxiesProperties trustedProxies) {
    this.credentialLimiter = credentialLimiter;
    this.refreshLimiter = refreshLimiter;
    this.messageLimiter = messageLimiter;
    this.passwordResetLimiter = passwordResetLimiter;
    this.totpLimiter = totpLimiter;
    this.emailVerificationLimiter = emailVerificationLimiter;
    this.reportLimiter = reportLimiter;
    this.dataExportLimiter = dataExportLimiter;
    this.cspReportLimiter = cspReportLimiter;
    this.telemetryErrorLimiter = telemetryErrorLimiter;
    this.stepUpLimiter = stepUpLimiter;
    this.listingCreateLimiter = listingCreateLimiter;
    this.orderCreateLimiter = orderCreateLimiter;
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
    if (!"POST".equalsIgnoreCase(request.getMethod())) {
      return true;
    }
    if (MESSAGE_PATH.equals(path)) {
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
    if (ITEMS_PATH.equals(path)) {
      // Listing creation (backlog #219): own per-user bucket on the
      // message keying (JWT principal, per-IP fallback), never reset
      // on success — a created listing must not refill the spam
      // allowance. The exact match keeps the sibling item POSTs
      // (/photo, /photos, /renew, /publish) out of this bucket.
      return !listingCreateLimiter.isEnabled();
    }
    if (ORDERS_PATH.equals(path)) {
      // Order creation (backlog #219): own per-user bucket on the
      // message keying, never reset on success — a placed order must
      // not refill the allowance. The exact match keeps the
      // transition POSTs (/pay, /cancel, ...) out of this bucket.
      return !orderCreateLimiter.isEnabled();
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
        writeTooManyRequests(response, dataExportLimiter.retryAfterSeconds(key));
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (MESSAGE_PATH.equals(path) || isMessageItemPath(path)) {
      String key = messageKey(request);
      if (!messageLimiter.tryConsume(key)) {
        writeTooManyRequests(response, messageLimiter.retryAfterSeconds(key));
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
        writeTooManyRequests(response, reportLimiter.retryAfterSeconds(key));
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
        writeTooManyRequests(response, cspReportLimiter.retryAfterSeconds(ip));
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
        writeTooManyRequests(response, telemetryErrorLimiter.retryAfterSeconds(ip));
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
        writeTooManyRequests(response, stepUpLimiter.retryAfterSeconds(key));
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
        writeTooManyRequests(response, listingCreateLimiter.retryAfterSeconds(key));
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
        writeTooManyRequests(response, orderCreateLimiter.retryAfterSeconds(key));
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
        writeTooManyRequests(response, passwordResetLimiter.retryAfterSeconds(ip));
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
        writeTooManyRequests(response, totpLimiter.retryAfterSeconds(ip));
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
        writeTooManyRequests(response, emailVerificationLimiter.retryAfterSeconds(ip));
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    AuthRateLimiter limiter = REFRESH_PATH.equals(path) ? refreshLimiter : credentialLimiter;
    String ip = clientIp(request);
    if (!limiter.tryConsume(ip)) {
      writeTooManyRequests(response, limiter.retryAfterSeconds(ip));
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

  private void writeTooManyRequests(HttpServletResponse response, long retryAfterSeconds)
      throws IOException {
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.setHeader(RETRY_AFTER_HEADER, Long.toString(retryAfterSeconds));
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
