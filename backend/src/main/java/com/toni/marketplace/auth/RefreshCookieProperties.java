package com.toni.marketplace.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Refresh-cookie {@code Secure} policy (backlog #130).
 *
 * <p>Binds {@code app.auth.cookie-secure} and
 * {@code app.auth.allow-insecure-cookie}. The httpOnly {@code refresh_token}
 * cookie is a long-lived credential (7 d): on an HTTPS deployment it must
 * carry the {@code Secure} attribute so the browser never sends it over
 * plain HTTP. Local development runs plain HTTP, where a {@code Secure}
 * cookie would never be stored at all — so the flag defaults to
 * {@code false} and {@link AuthController} threads the configured value
 * through BOTH cookie builders (the set cookie and the logout
 * clear-cookie must agree, or the browser will not overwrite the stored
 * cookie).
 *
 * <p>On the {@code mysql} profile {@link RefreshCookieSecureStartupCheck}
 * refuses to boot while the flag is still {@code false}, mirroring the
 * JWT-secret (#48) and TOTP-key (#89) fail-fast guards — unless the
 * operator sets {@code allow-insecure-cookie=true}, an explicit opt-out
 * documented as local-only (e.g. a mysql-profile run on a developer
 * machine that is deliberately not behind HTTPS).
 */
@ConfigurationProperties(prefix = "app.auth")
public class RefreshCookieProperties {

  /** Whether the refresh cookie carries the {@code Secure} attribute. */
  private boolean cookieSecure = false;

  /** Explicit local-only opt-out from the mysql-profile startup guard. */
  private boolean allowInsecureCookie = false;

  public boolean isCookieSecure() {
    return cookieSecure;
  }

  public void setCookieSecure(boolean cookieSecure) {
    this.cookieSecure = cookieSecure;
  }

  public boolean isAllowInsecureCookie() {
    return allowInsecureCookie;
  }

  public void setAllowInsecureCookie(boolean allowInsecureCookie) {
    this.allowInsecureCookie = allowInsecureCookie;
  }
}
