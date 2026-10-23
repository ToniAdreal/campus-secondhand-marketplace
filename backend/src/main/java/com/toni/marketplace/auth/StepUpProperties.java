package com.toni.marketplace.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Step-up re-authentication for sensitive actions (backlog #218).
 *
 * <p>Until now every sensitive self-service action improvised its own
 * proof — password re-check on change and closure, a second-factor code
 * on TOTP disable — while others (revoke other sessions, the data-export
 * grant) asked for no fresh proof at all, so a stolen 15-minute Bearer
 * token could perform them. Tokens now carry an {@code auth_time} claim (see
 * {@link JwtTokenService}): the instant of the holder's last credential
 * proof. {@link StepUpPolicy} gates the sensitive set on it: a token
 * whose proof is older than {@code maxAge} is refused with a dedicated
 * 403 step-up envelope (never a 401), and the SPA's re-auth modal
 * re-proves and retries the action once.
 *
 * <p>Defaults: 10 minutes of proof freshness. Override with e.g.
 * {@code app.auth.step-up.max-age=5m}. A non-positive value would make
 * every sensitive action refuse immediately — the property is honoured
 * verbatim and operators are expected to keep it sane; the boundary is
 * inclusive (a proof exactly maxAge old still counts as fresh).
 */
@ConfigurationProperties(prefix = "app.auth.step-up")
public class StepUpProperties {

  /** How long a credential proof stays fresh for the sensitive set. */
  private Duration maxAge = Duration.ofMinutes(10);

  public Duration getMaxAge() {
    return maxAge;
  }

  public void setMaxAge(Duration maxAge) {
    this.maxAge = maxAge;
  }
}
