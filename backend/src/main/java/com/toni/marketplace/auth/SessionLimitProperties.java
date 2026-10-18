package com.toni.marketplace.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Concurrent-session limit for one account (backlog #191).
 *
 * <p>Sessions are refresh-token families (see {@code SessionMeta} /
 * backlog #62): until now nothing capped how many live families one
 * account could hold, so a leaked refresh token plus the owner's own
 * logins accumulated indefinitely until the 30-day family max-age
 * (backlog #61). A successful login — the password path or the TOTP
 * exchange that completes one — that would exceed
 * {@code maxSessionsPerUser} revokes the OLDEST live family inside the
 * login's own transaction (the {@code revokeOtherSessions} discipline
 * of backlog #163) and records a {@code SESSION_EVICTED} notification,
 * so the eviction is visible to the account holder, never silent.
 *
 * <p>Defaults: 5 live sessions per user. Override with e.g.
 * {@code app.auth.max-sessions-per-user=3}. A value below 1 disables
 * enforcement (a misconfigured cap must never sign everybody out);
 * the property is honoured verbatim at every value &ge; 1.
 */
@ConfigurationProperties(prefix = "app.auth")
public class SessionLimitProperties {

  /** Live refresh-token families one account may hold at once. */
  private int maxSessionsPerUser = 5;

  public int getMaxSessionsPerUser() {
    return maxSessionsPerUser;
  }

  public void setMaxSessionsPerUser(int maxSessionsPerUser) {
    this.maxSessionsPerUser = maxSessionsPerUser;
  }
}
