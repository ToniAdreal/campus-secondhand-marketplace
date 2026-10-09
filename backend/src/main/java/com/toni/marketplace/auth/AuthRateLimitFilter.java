package com.toni.marketplace.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toni.marketplace.common.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
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
 * <p>Honest trade-offs, both documented here so they stay deliberate:
 * <ul>
 *   <li>The access token on a message send is parsed twice (once here to
 *       recover the principal id, once by the JWT authentication filter).
 *       This filter enforces nothing — it only throttles; all auth
 *       decisions stay downstream.</li>
 *   <li>Client identity is {@code request.getRemoteAddr()}. Behind the
 *       docker-compose nginx proxy that is the proxy's address, not the end
 *       user's — honoring {@code X-Forwarded-For} from trusted proxies is a
 *       documented follow-up, not done here (trusting the header blindly
 *       would let attackers spoof their bucket key).</li>
 * </ul>
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
  private static final String MESSAGE_PATH = "/api/messages";
  private static final String BEARER_PREFIX = "Bearer ";

  private final AuthRateLimiter credentialLimiter;
  private final AuthRateLimiter refreshLimiter;
  private final AuthRateLimiter messageLimiter;
  private final AuthRateLimiter passwordResetLimiter;
  private final AuthRateLimiter totpLimiter;
  private final JwtTokenService jwt;
  private final ObjectMapper mapper;

  public AuthRateLimitFilter(@Qualifier("credentialRateLimiter") AuthRateLimiter credentialLimiter,
                             @Qualifier("refreshRateLimiter") AuthRateLimiter refreshLimiter,
                             @Qualifier("messageRateLimiter") AuthRateLimiter messageLimiter,
                             @Qualifier("passwordResetRateLimiter")
                                 AuthRateLimiter passwordResetLimiter,
                             @Qualifier("totpRateLimiter") AuthRateLimiter totpLimiter,
                             JwtTokenService jwt,
                             ObjectMapper mapper) {
    this.credentialLimiter = credentialLimiter;
    this.refreshLimiter = refreshLimiter;
    this.messageLimiter = messageLimiter;
    this.passwordResetLimiter = passwordResetLimiter;
    this.totpLimiter = totpLimiter;
    this.jwt = jwt;
    this.mapper = mapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!"POST".equalsIgnoreCase(request.getMethod())) {
      return true;
    }
    String path = pathOf(request);
    if (MESSAGE_PATH.equals(path)) {
      return !messageLimiter.isEnabled();
    }
    if (RESET_PATH.equals(path) || RESET_CONFIRM_PATH.equals(path)) {
      return !passwordResetLimiter.isEnabled();
    }
    if (TOTP_AUTH_PATH.equals(path)) {
      return !totpLimiter.isEnabled();
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
    if (MESSAGE_PATH.equals(path)) {
      String key = messageKey(request);
      if (!messageLimiter.tryConsume(key)) {
        writeTooManyRequests(response, messageLimiter.retryAfterSeconds(key));
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

  private static String clientIp(HttpServletRequest request) {
    String ip = request.getRemoteAddr();
    return (ip == null || ip.isBlank()) ? "unknown" : ip;
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
