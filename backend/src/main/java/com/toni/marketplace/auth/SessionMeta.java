package com.toni.marketplace.auth;

import jakarta.servlet.http.HttpServletRequest;

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
 * the end user's (no X-Forwarded-For trust is configured).
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
    String userAgent = request.getHeader("User-Agent");
    if (userAgent != null && userAgent.length() > MAX_USER_AGENT_LENGTH) {
      userAgent = userAgent.substring(0, MAX_USER_AGENT_LENGTH);
    }
    return new SessionMeta(userAgent, request.getRemoteAddr());
  }
}
