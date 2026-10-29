package com.toni.marketplace.auth;

import com.toni.marketplace.common.StepUpRequiredException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Step-up re-authentication gate (backlog #218): decides whether the
 * presented access token carries a recent enough credential proof for
 * the sensitive set — sessions revoke-others, the data-export grant,
 * TOTP setup (backlog #250) and disable, account closure, passkey
 * enrolment/management, username change (backlog #261), and the
 * email-change request stage (backlog #272; the confirm step stays
 * token-gated, the mailed token is its credential).
 *
 * <p>The proof instant is the token's {@code auth_time} claim (see
 * {@link JwtTokenService}), read from the request's
 * {@link CurrentAccessClaims} holder. Rules:
 * <ul>
 *   <li>No request scope active (direct unit tests, scheduled jobs): the
 *       gate is a no-op — the provider is null, or resolving the
 *       request-scoped holder throws {@code IllegalStateException}. The
 *       sensitive methods are only reachable over HTTP in production,
 *       and the HTTP layer always runs inside a request scope — the
 *       filter-set holder is the enforcement point, pinned by the
 *       MockMvc suite.</li>
 *   <li>Holder resolves but is empty (no access token was presented on
 *       this request): also a no-op. The security chain answers 401
 *       before any gated service method runs — every gated endpoint
 *       requires authentication — so over HTTP this is unreachable; it
 *       only happens on direct service calls outside a request
 *       (full-context tests, jobs), where the gate must not fire.</li>
 *   <li>Claims present but the {@code auth_time} claim absent (tokens minted
 *       before #218): stale — a token that cannot prove its proof
 *       instant must never be treated as proven.</li>
 *   <li>Proof older than {@code app.auth.step-up.max-age} (default
 *       10 min): stale. The boundary is inclusive — a proof exactly
 *       maxAge old still counts as fresh.</li>
 * </ul>
 *
 * <p>A stale token throws {@link StepUpRequiredException} → the
 * dedicated 403 step-up envelope (never a 401, which would claim the
 * credential itself is bad). Refresh rotation never freshens
 * {@code auth_time} (see {@code JwtTokenService.rotateLive}); only a real
 * proof event — login, TOTP-exchange completion, password change,
 * registration, or the {@code POST /api/auth/step-up} endpoint —
 * mints a token with a fresh claim.
 */
@Component
public class StepUpPolicy {

  private final StepUpProperties properties;
  private final Clock clock;
  private final ObjectProvider<CurrentAccessClaims> current;

  public StepUpPolicy(StepUpProperties properties, Clock clock,
                      ObjectProvider<CurrentAccessClaims> current) {
    this.properties = properties;
    this.clock = clock;
    this.current = current;
  }

  /** No-op instance for hand-built services outside a request scope. */
  public static StepUpPolicy disabled() {
    return new StepUpPolicy(new StepUpProperties(), Clock.systemUTC(), null);
  }

  /**
   * Throws {@link StepUpRequiredException} unless the current request's
   * access token proves a credential within the freshness window.
   */
  public void requireFresh() {
    if (!isFresh()) {
      throw new StepUpRequiredException();
    }
  }

  /**
   * Whether the current request's access token is fresh enough for the
   * sensitive set. True when no request scope is active, when the holder
   * resolves but no token was presented (see the class javadoc — both are
   * the deliberate no-op for direct service calls), and when the proof is
   * within the freshness window. False for a presented token whose proof
   * is missing (pre-#218) or older than the configured max age.
   */
  public boolean isFresh() {
    if (current == null) {
      return true;
    }
    CurrentAccessClaims holder;
    try {
      holder = current.getIfAvailable();
    } catch (IllegalStateException ex) {
      // No request scope active (direct unit tests, scheduled jobs).
      return true;
    }
    if (holder == null) {
      return true;
    }
    JwtTokenService.AccessClaims claims;
    try {
      claims = holder.get();
    } catch (IllegalStateException ex) {
      // The scope went away mid-call: not a live HTTP request.
      return true;
    }
    if (claims == null) {
      // No token presented on this request — the security chain 401s
      // before any gated method runs, so this is only reachable on
      // direct service calls, where the gate is a deliberate no-op.
      return true;
    }
    if (claims.authTime() == null) {
      // Token minted before #218: no proof instant — stale, never fresh.
      return false;
    }
    Instant cutoff = clock.instant().minus(properties.getMaxAge());
    return !claims.authTime().isBefore(cutoff);
  }
}
