package com.toni.marketplace.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Breached-password screening knobs (backlog #197), bound from
 * {@code app.auth.breached-password.*}.
 *
 * <p>The DEFAULT is the offline {@link LocalDenylistBreachedPasswordChecker}:
 * a small committed denylist, no network call, ever. Setting
 * {@code remote-enabled=true} ({@code APP_AUTH_BREACHED_PASSWORD_REMOTE_ENABLED})
 * is the operator's explicit opt-in to the k-anonymity range lookup in
 * {@link HttpBreachedPasswordChecker} against {@code base-url} — it is
 * never enabled silently, and the local denylist is then replaced (not
 * layered), so the failure semantics are exactly one checker's: the
 * remote checker is fail-open, the local one fail-closed.
 */
@ConfigurationProperties(prefix = "app.auth.breached-password")
public class BreachedPasswordProperties {

  /** Whether the remote (HIBP-shaped) range checker replaces the local denylist. */
  private boolean remoteEnabled = false;

  /** Range-endpoint base URL; the 5-hex-char prefix is appended as one path segment. */
  private String baseUrl = "https://api.pwnedpasswords.com/range";

  /** Per-request timeout for the remote range lookup. */
  private Duration timeout = Duration.ofSeconds(2);

  public boolean isRemoteEnabled() {
    return remoteEnabled;
  }

  public void setRemoteEnabled(boolean remoteEnabled) {
    this.remoteEnabled = remoteEnabled;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public Duration getTimeout() {
    return timeout;
  }

  public void setTimeout(Duration timeout) {
    this.timeout = timeout;
  }
}
