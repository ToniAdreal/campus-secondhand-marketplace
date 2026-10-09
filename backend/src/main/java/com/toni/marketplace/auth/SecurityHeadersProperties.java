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
 * <p>Content-Security-Policy (backlog #80, the follow-up #64 declared) is
 * likewise opt-in via {@code app.security.headers.csp-enabled}: off by
 * default so an operator enables it deliberately, on with the strict
 * policy declared in {@code SecurityConfig} ({@code default-src 'self'}
 * with no {@code 'unsafe-inline'} anywhere — verified against the real
 * Vite build output: {@code dist/index.html} references only external
 * {@code /assets/*.js} / {@code *.css} files, and the SPA source uses no
 * inline {@code style} props, {@code eval} or inline event handlers, so
 * the bundled app needs no inline allowance).
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

  /**
   * Send {@code Content-Security-Policy} (the policy string lives in
   * {@code SecurityConfig}) on responses. Default {@code false}, mirroring
   * the HSTS switch: the header is only meaningful for the browser-served
   * SPA, and enabling it is an operator decision — turn it on
   * ({@code app.security.headers.csp-enabled=true}) for any deployment
   * that serves the built frontend.
   */
  private boolean cspEnabled = false;

  public boolean isCspEnabled() {
    return cspEnabled;
  }

  public void setCspEnabled(boolean cspEnabled) {
    this.cspEnabled = cspEnabled;
  }
}
