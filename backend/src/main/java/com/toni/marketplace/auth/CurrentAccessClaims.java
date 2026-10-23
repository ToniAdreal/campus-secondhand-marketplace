package com.toni.marketplace.auth;

import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

/**
 * The access-token claims of the current request (backlog #218),
 * installed by {@link JwtAuthenticationFilter} after it validates the
 * Bearer token. Request-scoped: every request gets a fresh holder, so
 * {@link StepUpPolicy} can gate sensitive service actions on the
 * presented token's {@code auth_time} claim without the services ever
 * touching the raw token.
 *
 * <p>Unauthenticated requests leave the holder empty (the security
 * chain answers 401 before any gated action runs); a failed parse also
 * leaves it empty. {@link StepUpPolicy} treats an empty holder as a
 * no-op: over HTTP the gated endpoints all require authentication, so
 * an empty holder at a service method only happens on direct calls
 * outside a request (full-context tests, jobs) — never from a stolen
 * token, which always presents claims.
 */
@Component
@Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
public class CurrentAccessClaims {

  private JwtTokenService.AccessClaims claims;

  public void set(JwtTokenService.AccessClaims claims) {
    this.claims = claims;
  }

  public JwtTokenService.AccessClaims get() {
    return claims;
  }

  public void clear() {
    this.claims = null;
  }
}
