package com.toni.marketplace.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Security response headers (backlog #64).
 *
 * <p>Before #64 nothing about response headers was declared in
 * {@code SecurityConfig} — no {@code X-Content-Type-Options}, no
 * {@code X-Frame-Options}, no {@code Referrer-Policy}, and HSTS was left on
 * Spring Security's default (which only engages on HTTPS requests anyway).
 * The chain now sends {@code X-Content-Type-Options: nosniff},
 * {@code X-Frame-Options: DENY} and {@code Referrer-Policy: no-referrer}
 * on every response, and HSTS ({@code Strict-Transport-Security}) only when
 * this switch is on.
 *
 * <p>HSTS is off by default because local development (Vite on
 * {@code http://localhost:5173}, backend on {@code http://localhost:8080})
 * is plain HTTP: sending {@code Strict-Transport-Security} over HTTP would
 * be a no-op at best and misleading at worst. An HTTPS deployment (the
 * docker-compose nginx profile with TLS, or any reverse proxy terminating
 * TLS) turns it on with {@code app.security.headers.hsts-enabled=true}.
 *
 * <p>Honest scope: headers only. There is no Content-Security-Policy yet —
 * a strict CSP that doesn't break the SPA's inline scripts is a separate
 * round (declared follow-up).
 */
@ConfigurationProperties(prefix = "app.security.headers")
public class SecurityHeadersProperties {

  /**
   * Send {@code Strict-Transport-Security} (HSTS) on responses. Default
   * {@code false}: local http dev must not declare it. Enable on any
   * HTTPS-served deployment.
   */
  private boolean hstsEnabled = false;

  public boolean isHstsEnabled() {
    return hstsEnabled;
  }

  public void setHstsEnabled(boolean hstsEnabled) {
    this.hstsEnabled = hstsEnabled;
  }
}
