package com.toni.marketplace.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;

/**
 * Device info captured at login/refresh time for per-session management
 * (backlog #62). The User-Agent header becomes the session's device label
 * and the remote address its IP; both are recorded on the refresh-token row
 * (Flyway V13) so {@code GET /api/auth/sessions} can show "which device is
 * which". Capture is best-effort: a missing User-Agent stores NULL.
 *
 * <p>Honest scope: this is whatever the client sent — a user agent is not
 * an authenticated device identity, and behind a proxy
 * {@link HttpServletRequest#getRemoteAddr()} sees the proxy's address, not
 * the end user's. The plain {@link #of(HttpServletRequest)} factory keeps
 * that behaviour; the login-history capture (backlog #171) uses
 * {@link #of(HttpServletRequest, Set)} so its rows carry the same
 * trusted-proxy client IP the #104 rate-limit resolution computes.
 */
public record SessionMeta(String userAgent, String ipAddress) {

  /** Must match the refresh_token.user_agent column length (Flyway V13). */
  static final int MAX_USER_AGENT_LENGTH = 512;

  /** The capture path is not available (service/test call sites). */
  public static SessionMeta unknown() {
    return new SessionMeta(null, null);
  }

  /** Extracts device info from the current request, truncating the UA to the column width. */
  public static SessionMeta of(HttpServletRequest request) {
    return of(request, Set.of());
  }

  /**
   * Extracts device info like {@link #of(HttpServletRequest)}, but resolves
   * the IP through the #104 trusted-proxy resolution
   * ({@link AuthRateLimitFilter#resolveClientIp}): {@code X-Forwarded-For}
   * is honored only when the immediate peer is in {@code trustedProxies}.
   * With an empty set the resolved IP is exactly the remote address (or
   * {@code "unknown"} when the request carries none), so the plain factory
   * above delegates here unchanged in behaviour.
   */
  public static SessionMeta of(HttpServletRequest request, Set<String> trustedProxies) {
    String userAgent = request.getHeader("User-Agent");
    if (userAgent != null && userAgent.length() > MAX_USER_AGENT_LENGTH) {
      userAgent = userAgent.substring(0, MAX_USER_AGENT_LENGTH);
    }
    String ipAddress = AuthRateLimitFilter.resolveClientIp(request.getRemoteAddr(),
        request.getHeader("X-Forwarded-For"), trustedProxies);
    return new SessionMeta(userAgent, ipAddress);
  }
}
